package com.s2p.core.insight;

import com.s2p.contracts.Planning;
import com.s2p.core.CoreContext;
import com.s2p.core.ai.AiDtos;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.state.CentralView;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a question with the language model and turns it into one call of an existing service. The model chooses
 * the tool and fills its arguments from the question (including dates such as "the 18th" or "last week"); the core
 * decides which tools a role may use, checks every argument against current data, and runs the service itself. The
 * model never computes the answer.
 *
 * Safety is in the tool design, not the prompt: no tool can skip a check, and the only way to ask for a promise the
 * plan does not support is {@code request_override}, which declines and routes to the Head of Operations.
 */
public final class AskPlanner {
    /** How a question was read: the intent, the arguments to run it with, and one line to show the user. */
    public record Routed(AskService.Intent intent, Map<String, String> context, String readAs) {
    }

    private static final Map<String, Object> NONE = schema(Map.of(), List.of());
    private static final List<AiDtos.ToolSpec> TOOLS = List.of(
            new AiDtos.ToolSpec("check_promise", "Check whether a quantity of a product can be delivered to a customer "
                    + "by a date, or find the earliest date. Use for 'can we promise/deliver/do X by Y', 'is the 20th "
                    + "realistic', 'how soon can we ship'.", schema(Map.of(
                    "sku", prop("string", "Catalogue SKU of the product, from the products list"),
                    "qty", prop("integer", "Quantity in units"),
                    "date", prop("string", "Requested delivery date as YYYY-MM-DD, resolved against today; omit if none"),
                    "account_id", prop("string", "Customer account id from the accounts list")), List.of())),
            new AiDtos.ToolSpec("request_override", "The user asks to promise a date anyway, to ignore or skip the stock "
                    + "or capacity check, or to override the plan.", schema(Map.of(
                    "sku", prop("string", "Catalogue SKU, if known"),
                    "qty", prop("integer", "Quantity, if known"),
                    "date", prop("string", "Date to promise as YYYY-MM-DD, if known"),
                    "account_id", prop("string", "Customer account id, if known")), List.of())),
            new AiDtos.ToolSpec("line_slip_impact", "A production line slipped, broke down or lost days: re-plan and "
                    + "show the orders at risk.", schema(Map.of(
                    "line_id", prop("string", "Line id from the lines list, for example L2"),
                    "working_days", prop("integer", "Working days lost, if stated")), List.of("line_id"))),
            new AiDtos.ToolSpec("customer_order_status", "Where is my/our order; the status or delivery date of an order.",
                    schema(Map.of("order_ref", prop("string", "Order number or PO if the question names one")), List.of())),
            new AiDtos.ToolSpec("quotation_risk", "Which open quotations are most likely to be lost and what to do about "
                    + "them.", NONE),
            new AiDtos.ToolSpec("trace_decision", "Why did the system tell a customer something, or what data was a past "
                    + "statement based on.", schema(Map.of(
                    "account_id", prop("string", "Customer account id, if the question names a customer"),
                    "order_id", prop("string", "Order id, if named"),
                    "date_stated", prop("string", "The date the customer was told, as YYYY-MM-DD (\"the 18th\" is the "
                            + "18th nearest to today), if the question names one"),
                    "said_from", prop("string", "Start of the period when it was said, YYYY-MM-DD (\"last week\": "
                            + "Monday of last week)"),
                    "said_to", prop("string", "End of that period, YYYY-MM-DD (\"last week\": Sunday of last week)")),
                    List.of())),
            new AiDtos.ToolSpec("self_state", "How fresh the data is, how confident the system is, what it does not "
                    + "trust, what is pending.", NONE),
            new AiDtos.ToolSpec("cannot_answer", "None of the other tools fits the question.",
                    schema(Map.of("reason", prop("string", "Why no tool fits")), List.of())));

    private final CoreContext ctx;
    private final AiGateway ai;

    public AskPlanner(CoreContext ctx, AiGateway ai) {
        this.ctx = ctx;
        this.ai = ai;
    }

    public boolean enabled() {
        return ai.enabled();
    }

    /** Tools a role may use. Enforced here, whatever the model returns. */
    static List<AiDtos.ToolSpec> toolsFor(String role) {
        if ("CUSTOMER".equals(role)) {
            return TOOLS.stream().filter(t -> t.name().equals("customer_order_status") || t.name().equals("cannot_answer"))
                    .toList();
        }
        return TOOLS;
    }

    public Optional<Routed> route(String role, String question, Map<String, String> explicit) {
        if (!ai.enabled()) {
            return Optional.empty();
        }
        CentralView v = ctx.state().view();
        List<AiDtos.ToolSpec> tools = toolsFor(role);
        Map<String, String> hints = new LinkedHashMap<>(explicit);
        hints.remove("token");
        AiDtos.AskPlanRequest req = new AiDtos.AskPlanRequest(question, role, v.today().toString(), tools,
                v.crm().accounts().stream().map(a -> new AiDtos.EntityRef(a.id(), a.name())).toList(),
                v.erp().products().stream().map(p -> new AiDtos.EntityRef(p.sku(), p.name())).toList(),
                v.planning().lines().stream().map(Planning.Line::id).toList(), hints);
        Optional<AiDtos.AskPlan> plan = ai.planAsk(req);
        if (plan.isEmpty() || plan.get().tool() == null) {
            return Optional.empty();
        }
        AiDtos.AskPlan p = plan.get();
        if (tools.stream().noneMatch(t -> t.name().equals(p.tool()))) {
            return Optional.of(new Routed(AskService.Intent.UNKNOWN, explicit, "The model chose '" + p.tool()
                    + "', which the role " + role + " may not use; nothing was run."));
        }
        return Optional.of(validate(p, explicit, v));
    }

    private Routed validate(AiDtos.AskPlan p, Map<String, String> explicit, CentralView v) {
        Map<String, String> c = new LinkedHashMap<>(explicit);
        List<String> read = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        Map<String, Object> a = p.args();
        AskService.Intent intent = switch (p.tool()) {
            case "check_promise" -> AskService.Intent.PROMISE;
            case "request_override" -> AskService.Intent.OVERRIDE;
            case "line_slip_impact" -> AskService.Intent.LINE_SLIP;
            case "customer_order_status" -> AskService.Intent.CUSTOMER_STATUS;
            case "quotation_risk" -> AskService.Intent.QUOTE_RISK;
            case "trace_decision" -> AskService.Intent.TRACE;
            case "self_state" -> AskService.Intent.SELF;
            default -> AskService.Intent.UNKNOWN;
        };
        String sku = str(a.get("sku"));
        if (sku != null) {
            String s = sku.toUpperCase(Locale.ROOT);
            accept(c, "sku", v.product(s).isPresent() ? s : null, "product " + sku + " is not in the catalogue", read, ignored);
        }
        String account = str(a.get("account_id"));
        if (account != null) {
            accept(c, "accountId", v.account(account).isPresent() ? account : null,
                    "customer " + account + " is not in the CRM", read, ignored);
        }
        Integer qty = integer(a.get("qty"));
        if (a.get("qty") != null) {
            accept(c, "qty", qty != null && qty > 0 ? String.valueOf(qty) : null, "quantity " + a.get("qty")
                    + " is not a positive whole number", read, ignored);
        }
        String date = str(a.get("date"));
        if (date != null) {
            LocalDate d = iso(date);
            accept(c, "date", d != null && !d.isBefore(v.today()) ? d.toString() : null, "date " + date
                    + " is not a date from today on", read, ignored);
        }
        for (String[] k : new String[][]{{"date_stated", "dateStated"}, {"said_from", "saidFrom"}, {"said_to", "saidTo"}}) {
            String s = str(a.get(k[0]));
            if (s != null) {
                LocalDate d = iso(s);
                boolean past = !k[0].startsWith("said") || (d != null && !d.isAfter(v.today()));
                accept(c, k[1], d != null && past ? d.toString() : null, k[0] + " " + s + " is not a valid date"
                        + (past ? "" : " in the past"), read, ignored);
            }
        }
        String line = str(a.get("line_id"));
        if (line != null) {
            String l = line.toUpperCase(Locale.ROOT).replace("LINE", "L").replace(" ", "");
            accept(c, "lineId", v.line(l).isPresent() ? l : null, "line " + line + " is not a planning line", read, ignored);
        }
        String orderRef = str(a.get("order_ref"));
        if (orderRef != null && orderRef.length() <= 40) {
            accept(c, "orderRef", orderRef, null, read, ignored);
        }
        String orderId = str(a.get("order_id"));
        if (orderId != null) {
            accept(c, "orderId", v.order(orderId).isPresent() ? orderId : null, "order " + orderId + " is not in the ERP",
                    read, ignored);
        }
        StringBuilder s = new StringBuilder("Read by the model as ").append(p.tool());
        if (!read.isEmpty()) {
            s.append("(").append(String.join(", ", read)).append(")");
        }
        if (p.reading() != null && !p.reading().isBlank()) {
            s.append(": \"").append(p.reading().trim()).append("\"");
        }
        if (!ignored.isEmpty()) {
            s.append(". Ignored: ").append(String.join("; ", ignored));
        }
        s.append(". If that is not what you meant, name the customer, product and date and ask again.");
        return new Routed(intent, c, s.toString());
    }

    /** The user's explicit selection wins over the model's reading; a disagreement is shown. */
    private static void accept(Map<String, String> c, String key, String value, String why, List<String> read,
                               List<String> ignored) {
        if (value == null) {
            if (why != null) {
                ignored.add(why);
            }
            return;
        }
        String chosen = c.get(key);
        if (chosen != null && !chosen.equals(value)) {
            read.add(key + "=" + chosen + " (your selection; the question suggests " + value + ")");
            return;
        }
        c.put(key, value);
        read.add(key + "=" + value);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        return s;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }

    private static Integer integer(Object o) {
        if (o instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.valueOf(String.valueOf(o).trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate iso(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
