package com.s2p.core;

import com.s2p.core.ai.AiDtos;
import com.s2p.core.ai.AiGateway;
import com.s2p.core.ai.AiTelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** A scripted language model for tests: each method answers from a function of the request and records requests. */
public final class FakeAi implements AiGateway {
    public Function<AiDtos.ExtractRequest, AiDtos.ExtractResponse> extract = r -> null;
    public Function<AiDtos.AskPlanRequest, AiDtos.AskPlan> plan = r -> null;
    public Function<AiDtos.ActivitiesRequest, AiDtos.ActivitiesResponse> activities = r -> null;
    public final List<AiDtos.ExtractRequest> extractCalls = new ArrayList<>();
    public final List<AiDtos.AskPlanRequest> planCalls = new ArrayList<>();
    private final AiTelemetry telemetry = new AiTelemetry();

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String describeConfig() {
        return "fake model for tests";
    }

    @Override
    public AiTelemetry telemetry() {
        return telemetry;
    }

    @Override
    public Optional<AiDtos.ExtractResponse> extract(AiDtos.ExtractRequest request) {
        extractCalls.add(request);
        return record(extract.apply(request));
    }

    @Override
    public Optional<AiDtos.AskPlan> planAsk(AiDtos.AskPlanRequest request) {
        planCalls.add(request);
        return record(plan.apply(request));
    }

    @Override
    public Optional<AiDtos.ActivitiesResponse> readActivities(AiDtos.ActivitiesRequest request) {
        return record(activities.apply(request));
    }

    private <T> Optional<T> record(T value) {
        if (value == null) {
            telemetry.failure("fake", "scripted unavailable");
            return Optional.empty();
        }
        telemetry.success("fake", 1);
        return Optional.of(value);
    }

    // ------------------------------------------------------------------ helpers to build model replies

    public static <T> AiDtos.Field<T> f(T value, String evidence) {
        return new AiDtos.Field<>(value, evidence);
    }

    /** A line as the model returns it: product by sku or candidates, quantity and date with their quoted text. */
    public static AiDtos.Line line(String product, String sku, java.util.List<String> candidates, String productEv,
                                   Integer qty, String qtyEv, String datePhrase, String iso) {
        return new AiDtos.Line(product, sku, candidates, productEv, qty == null ? null : f(qty, qtyEv), null,
                datePhrase == null ? null : new AiDtos.DateField(datePhrase, iso, datePhrase));
    }

    public static AiDtos.ExtractResponse reply(String intent, String intentEv, List<AiDtos.Line> lines) {
        return new AiDtos.ExtractResponse(f(intent, intentEv), null, null, null, null, lines, List.of(), "fake-gemini");
    }
}
