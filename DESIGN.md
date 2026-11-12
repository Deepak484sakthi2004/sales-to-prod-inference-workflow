# Design note

Diagram of the processes, components and every API: [`architecture.excalidraw`](architecture.excalidraw) (open at excalidraw.com). Run log: [`RUN_LOG.txt`](RUN_LOG.txt).

**Shape.** Seven processes. Five stand-ins (mailbox :8081, CRM :8082, ERP :8083, planning :8084, status feed :8085) run from one Spring Boot jar, each reachable only through its REST interface. The intelligence layer, core :8080, is plain Java behind port interfaces, wired to HTTP adapters live and to in-process adapters in tests and the run-log generator; it serves the plant desk and the customer portal (`/api/*`, `/customer/api/orders`). The AI service :8090 (Python, FastAPI) puts Gemini behind three calls: read a mail (`/v1/extract`), read a question (`/v1/ask/plan`), read CRM notes (`/v1/activities`). It reaches no business system and executes nothing.

**Language model for language, engine for numbers.** All reading of human language goes through the model: customer mail and attachments, questions in plain words (including "the 18th" and "last week"), and CRM notes (competitor mentions). The core has no pattern-based reader. The model reads; the engine decides. Every value the model reads quotes the text it came from, and the core drops it unless that text is in the source, a quantity matches the digits quoted, a date agrees with the day and month in its phrase, and a product exists, fits any size given and is named by the customer's own words where the catalogue has several of that kind and size (otherwise order history decides, stated as an assumption, or the customer is asked). Questions become one call of an existing service, chosen by the model from the tools the role is allowed; the core checks every argument. Answers are always the engine's own text. When the model is down nothing is guessed: mail stays unread and is counted in the self-report, and a question is answered with "I cannot read questions right now" (a promise picked in the form still runs).

## 1. The central state: what is truth, what is a copy

Each system of record owns its entities: the ERP owns prices, stock, quotations and orders; planning owns calendars, routings and work orders; the CRM owns accounts and activity; the status feed owns what customers see. The core never edits a copy; every change is a command to the owner. The core owns only its own facts: an append-only decision log, the action queue, the suggestion board, the enquiry ledger and its readings of CRM notes (kept until the notes change). They live in a `CoreStore`: in memory by default, or MongoDB (Atlas) with `S2P_STORE=mongo`, where they survive restarts and can be shared by several core instances (a decision first claims its action, PENDING to DECIDING, so two approvers cannot both execute it).

The `StateStore` keeps the last good copy of each source with its version, its own as-of time, the fetch time and the last error. Every question is answered from one `CentralView` built from those copies, so sales, planning and status read the same numbers, and every figure in the planner view has an explain key (`atp:`, `free:`, `eta:`, `value:`, `slack:`) returning its formula and source facts.

Consistency rests on five rules: **refresh on read**, so the next question sees the last write; **idempotent writes at the source of truth** (`QUOTE:<message-id>`, `ORDER:<quotation>`, `WO:<order:sku:line>`), so duplicates, retries and a restarted core cannot create a second quotation, order or work order; **planning enforces capacity** and applies multi-order reschedules atomically; **the status feed only moves forward** (an older sequence is rejected); and **re-check before anything leaves**: a customer-facing date is re-checked at execution, and if capacity vanished after approval the action becomes INVALIDATED and nothing is sent.

## 2. How the system knows its confidence and freshness

**Freshness** is now minus the source's own as-of time, so a lagging shop-floor feed looks stale even when the fetch succeeded. Each source has an SLA (planning 30 min, ERP 15). Past a 2-hour commit limit, answers are *indicative only*, no commitment can be made, and customer status is frozen rather than guessed. An unreachable source keeps its last copy, its age grows, and the failure is reported.

**Confidence** is a product of named factors, each with its reason: slack of the date presented; the main line's history (Line 2 finished 64% of work orders on time); reliance on inbound material weighted by the supplier's on-time rate; alternate-line first-article risk; inferred inputs (a product resolved from order history, "the 20th" read as Fri 20 Nov); and data age.

**"What would change the answer" is computed**: the engine is re-run with one thing changed (a lost day on the main line, the forging PO three days late, reserved stock released, the alternate line unavailable), giving lines such as "if PO-7781 is 3 days late, only 210 of 250 make the 20th." `Assessment<T>` cannot be constructed without confidence and freshness, and also carries its basis and what was not checked.

**It reads its own state**: data ages, decisions waiting, unprocessed mail, per-line forecast accuracy, its own promise hit rate from the decision log (7 of 10), the quotation model's Brier score against the base rate (0.129 vs 0.245), and its use of the language model (calls, failures, readings rejected, mail waiting to be read).

## 3. How suggestions are formed and ranked

**Formed from the same engine.** For an order at risk, the scheduler is re-run with one lever changed: overtime sized to the shortfall, the alternate line, both, resequencing ahead of an order with slack (kept only if that order stays on time), a split delivery, or telling the customer early. For quotations, the loss model is re-scored with the signal an action changes: a call halves days of silence, a re-quote at the last price paid stays above a 12% margin floor, a split is checked against today's capacity.

**Priced and ranked against doing nothing.** Lateness costs the order's contractual penalty plus goodwill per late day by tier (an explicit policy number, always shown separately). Expected loss if acted = p(works) × loss(new date) + (1 − p) × loss(do nothing) + cost. Ranking is by net benefit over doing nothing; the do-nothing row is always shown, and options that do not beat it are never suggested. `Suggestion` cannot be constructed without outcome, action, cost and risk if nobody acts, and carries break-even conditions ("worth it while it costs less than ₹23,460"; "stops beating doing nothing if goodwill per late day for tier A is below 1.03%").

**A person decides.** Accepting a suggestion creates and approves the action under that person's name, with role checks. Four rules approved in advance by a named person cover low-risk actions, and each execution records the rule id: R1 publishes on-plan progress; R2 reserves stock for a confirmed order; R3 schedules when slack is at least 3 days and value at most ₹5 lakh; R4 withdraws an estimate the plan no longer supports, never giving a new date. "Ignore the stock check" is declined, answered with what is supported, and routed to the Head of Operations.

**Changing a requirement** is one policy value (`POST /api/policy/...` or configuration), recorded in the decision log. Run log S06: a 1-day buffer turns "250 by Fri 20 Nov: yes" into "176 by Fri 20 Nov, all 250 by Sat 21 Nov."

## 4. What I chose not to build

- **No model in any decision.** Gemini reads text only; dates, quantities, prices, probabilities, rankings and recovery options stay deterministic. No labelled mail set yet, so the model's accuracy is reported as rejected readings, not measured. Tests use a scripted stand-in for the model.
- **No authentication.** Internal roles are trusted as passed; customers are identified by token.
- **Systems of record are still read as whole snapshots** through the ports, and re-planning keeps its event cursor in memory (rebuilt at start). No outbox: commands to the sources rely on their idempotency keys.
- **Simplified material pegging; greedy recovery** (one lever, one order at a time); **polling every 30 seconds**, not events.
- **No carrier, finance or HR roster integration**; each appears under "not checked" when it matters.

## What I would not trust yet

- **Goodwill cost per late day.** It decides whether overtime beats an early notice and is not measured; proven wrong if churn after late deliveries does not track tier and delay as assumed.
- **The quotation model's weights.** Hand-set, checked on only 14 closed quotations; proven wrong if a re-fit on the next 30 scores worse than the base-rate guess.
- **Zero-slack promises on Line 2.** The system already flags this: confidence 0.60 on the 250-by-the-20th answer.
- **The model's readings on real mail.** Checks catch invented values, not plausible misreadings that quote real text, and the tests use a scripted stand-in, not Gemini. Proven wrong if a labelled sample of 200 real mails shows checked readings below 98% correct on quantity, product and date.
