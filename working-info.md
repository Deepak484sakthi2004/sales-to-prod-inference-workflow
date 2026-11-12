Here is the whole codebase, end to end, following one customer order through it the way the code does.

## What it is, in one paragraph

Five small fake company systems (mailbox, CRM, ERP, production planning, status feed), one Java "brain" called core, and one Python service that talks to Gemini. Customers write in. Gemini reads the mail. Core's deterministic engine works out what can really be delivered and drafts a reply. A person approves, and core then keeps production and the customer's status page in line as things change. Every answer says how sure it is, how old its data is, and what would change it.

## The repository

| Folder | What lives there |
|---|---|
| `contracts/` | The shapes of data each system owns (`Mail`, `Crm`, `Erp`, `Planning`, `Status`), the `Ports` interfaces core uses to reach them, and `SimClock` (one shared business clock) |
| `sources/` | The five systems as plain Java stores (`MailboxStore`, `ErpStore`, …) loaded from `seed/*.json`. Seed dates are relative ("D+7") so the scenarios work on any day |
| `standins/` | Wraps each store in a REST controller. One jar, five processes (ports 8081–8085), chosen by Spring profile |
| `core/` | The intelligence layer, port 8080: engine, services, governance, the API and the browser UI |
| `ai-service/` | Python FastAPI, port 8090: the only place that calls Gemini |
| `docs/` | `DESIGN.md`, `RUN_LOG.txt`, `architecture.excalidraw` |
| `scripts/`, `start.sh` | One command to start everything; scripts to generate the run log |

## Startup

`start.sh` loads `.env` and starts the five stand-ins. If `GEMINI_API_KEY` is set, it also starts the AI service, then the core.

In the core, Spring's `CoreWiring` builds four things:
- `HttpPorts`: the adapters that call the stand-ins.
- `CoreStore`: in memory by default, or MongoDB if `S2P_STORE=mongo`.
- `AiGateway`: an HTTP client for the AI service, or "disabled".
- `CoreSystem`: the plain-Java object that owns all the services.

`CoreSystem.start()` loads every system's data once and records each order's current estimated delivery date. From then on, `Poller` calls `tick()` every 30 seconds. A tick does three things: read new mail, re-plan if planning reported events, and bring customer status in line.

Here is the journey one order takes through those parts:

## Step by step

**1. Mail arrives.** The mailbox stand-in holds the inbox. On each tick, `EnquiryService.poll()` reads the inbox through the central view and claims each new mail in the store's ledger. The claim is what stops a mail being read twice, even with two core instances running. Duplicates are caught before any model call: first by Message-ID, then by a fingerprint of the sender plus the normalised body (which catches resends). The sender is then matched to a CRM contact or company domain. An unknown sender gets an `UNKNOWN_SENDER` result and the mail is never sent to the model.

**2. The model reads it.** `EnquiryReader` builds a request with the mail, its attachments and the product catalogue. `HttpAiGateway` posts it to `/v1/extract`. In the Python service, `extract.py` sends Gemini a forced function call: the model must return structured JSON, and every value must come with the exact text it was read from. That covers intent, line items, the catalogue product (or the possible products when the customer didn't say which), quantity, date phrase and date, and PO and quote numbers.

Back in Java, `EnquiryReader` uses `TextCheck` to verify every value:
- The quoted text must appear in the mail.
- A quantity must match the digits in its quote.
- A product must exist, fit any size given, and be named by the customer's own words if the catalogue has several of that size.
- A date must agree with the day and month in its phrase.

Anything that fails is dropped and counted. The result is a `MailReading`. If the model can't be reached, the claim on the mail is released and it is read on a later tick; nothing is guessed.

**3. A quote is drafted.** `EnquiryService` routes on intent. For an enquiry:
- `resolve()` settles the product. If the model wasn't sure, the customer's order history breaks the tie, and that is stated as an assumption. Otherwise a clarification email is drafted.
- `PricingService` picks the price list valid today.
- `PromiseAssessor` runs `PromiseEngine`. It takes free stock first, then uses `Scheduler`, `CapacityModel` and `MaterialModel` to place the rest on production lines day by day, using the alternate line if policy allows. It returns the supported date, the slack and the binding constraint.
- The assessor builds the confidence from named factors (slack, the line's on-time history, reliance on incoming material, alternate-line risk, how the inputs were read). It also re-runs the engine with one thing changed to produce "what would change the answer".
- The quotation is created in the ERP under an idempotency key (`QUOTE:<message-id>`), so a retry can't create a second one.
- A `SEND_QUOTATION` action is proposed, and everything is written to the `DecisionLog`.

**4. The rep approves.** The plant desk calls `POST /api/actions/{id}/decision`. `ActionQueue.decide()` checks the person's role, then claims the action by moving it from PENDING to DECIDING, so two people can't both execute it. `ActionExecutor` then re-checks the date against fresh data before sending. If capacity vanished since the draft, the action becomes INVALIDATED and nothing leaves. Some low-risk actions skip the person under `AutoApprovalRules` (R1 to R4), which were approved in advance by a named person; the rule id is recorded.

**5. The customer confirms.** The reply is just another mail. The model reads it as ACCEPTANCE with a quotation number and PO. `acceptance()` creates the order under `ORDER:<quotation>`, so a second confirmation with different wording still yields one order.

**6. A plan is made.** `OrderPlanningService` turns the order into work orders on specific lines and days, and says which dates it can and cannot commit to. Creating the work orders is a `CREATE_WORK_ORDERS` action: a planner approves it, or rule R3 does when slack is large and the value small. The planning stand-in enforces capacity itself and refuses to overbook.

**7. A line slips.** The planning system records an event. On the next tick `ReplanService` sees it and recomputes every order's estimated date. For each order now at risk, it re-runs the scheduler with one lever changed: overtime, the alternate line, both, resequencing, a split delivery, or telling the customer early. Each option is priced against doing nothing: a contractual penalty plus goodwill per late day, weighted by the chance the option works, plus its cost. Options that don't beat doing nothing are dropped. The rest become `Suggestion`s on the `SuggestionBoard`, and a person accepts one.

**8. The customer sees status.** `StatusService.sync()` publishes each order's stage and estimate to the status feed. Only R1 (publish on-plan progress) and R4 (withdraw an estimate the plan no longer supports) run without a person. The customer portal calls `/customer/api/orders` with a token. The response contains only that customer's orders, the published stage and its age: no internal lines or reasons. The feed rejects updates older than what it shows, and stale planning data freezes status rather than guessing.

## Questions in plain words

The ask bar posts to `/api/ask`. `AskService.answer()` hands the question to `AskPlanner`, which sends it to `/v1/ask/plan` with the tools the user's role may use (a customer gets only order status). It also sends lists of customers, products and lines so the model can resolve "Sundaram" or "Line 2".

Gemini returns one tool plus its arguments. Java checks every argument against current data, and anything picked in the form wins over the model. Java then runs the matching service:
- `check_promise` runs the same engine as above.
- `request_override` declines and routes to the Head of Operations.
- `line_slip_impact` only re-plans if planning confirms the slip.
- `trace_decision` searches the decision log by the date stated and the period it was said in.
- `quotation_risk` and `self_state` run their reports.

The answer is always the service's own text, with a "Read as …" line showing what the model understood.

## The supporting pieces

**Central state.** `StateStore` keeps the last good copy of each system, with its version, its own as-of time and any error. Every answer is built from one `CentralView`, so sales, planning and the customer page see the same numbers. Freshness is "now minus the source's own timestamp"; past the time limit, answers become "indicative only". `ExplainService` returns the formula and source facts behind any figure in the planner view.

**Knowing itself.** `Assessment<T>` is the return type of nearly everything. It can't be built without confidence and freshness, and it also carries the basis, what was not checked, and what would change the answer. `SelfStateService` reports data ages, pending decisions, unread mail, each line's track record, its own promise hit rate, the quote model's Brier score and model telemetry.

**Quotation risk.** `QuotationRiskService` computes signals per open quotation: days silent, price gap to the last price paid, delivery gap, validity expiring, the account's win rate, and whether a competitor was mentioned. That last one comes from `ActivityReader`, which sends CRM notes to `/v1/activities` and keeps verified readings until the notes change. `QuoteRiskModel` is a small logistic model, checked against closed deals. Each quotation gets the action that most reduces expected loss after its cost.

**Persistence.** `CoreStore` holds only the core's own records: decisions, actions, suggestions, the mail ledger and CRM readings. Business data always stays in the five systems. `InMemoryCoreStore` is the default; `MongoCoreStore` keeps each record as `{_id, seq, idx, data}` plus atomic counters.

**The AI service.** It is six small files:
- `config.py`: settings from environment variables.
- `schemas.py`: the JSON contract with Java.
- `gemini.py`: the one SDK call.
- `extract.py`, `ask.py`, `activities.py`: one prompt and function schema per task.
- `main.py`: three endpoints plus `/health`. It returns 503 or 502 on failure so Java falls back.

It has no access to business systems and executes nothing.

**Frontend.** `index.html` plus `app/*.js` is the plant desk, with views for sales, planning, pipeline, operations and review. The review view can inject mail, slip a line, lag the feed or run all scenarios. `customer.html` is the customer portal.

## Tests and the run log

- **`PrinciplesTest`:** one nested group per principle in Section 4 of the brief.
- **Reading and questions:** `EnquiryReaderTest`, `AskPlannerTest` and `CrmNotesReadingTest` check the model checks.
- **Persistence:** `CoreStoreContractTest` and `PersistenceAcrossRestartTest` cover the stores, including MongoDB.
- **Java-to-Python contract:** `HttpAiGatewayTest`.

Tests use `ScriptedModel`, a hand-written stand-in for Gemini. `ScenarioSuite` runs the 17 review scenarios. `RunLogGenerator` runs it in-process to produce `docs/RUN_LOG.txt`, and `POST /api/scenarios/run` runs the same suite against the live stack with real Gemini.

## Where to start reading

Read in this order:
1. `CoreSystem.java`, to see every service and entry point.
2. `EnquiryService.process()` and `EnquiryReader`, for the journey above.
3. `PromiseEngine`, for how dates are decided.
4. `ActionQueue` and `ActionExecutor`, for how nothing leaves without a decision.
5. `AskService` and `AskPlanner`, for questions.
6. `ai-service/app/extract.py`, to see exactly what Gemini is asked.

Then read `RUN_LOG.txt`, scenarios S01 to S05, side by side with that code.
