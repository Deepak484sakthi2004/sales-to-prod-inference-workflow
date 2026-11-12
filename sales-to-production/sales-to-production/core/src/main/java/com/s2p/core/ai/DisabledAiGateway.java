package com.s2p.core.ai;

import java.util.Optional;

/** No model: mail waits unread and questions are not understood; promise checks still work from structured input. */
public final class DisabledAiGateway implements AiGateway {
    private final AiTelemetry telemetry = new AiTelemetry();

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String describeConfig() {
        return "language model disabled (s2p.ai.enabled=false): mail is not read and plain-language questions are "
                + "not understood; promise checks work from the customer, product, quantity and date pickers";
    }

    @Override
    public AiTelemetry telemetry() {
        return telemetry;
    }

    @Override
    public Optional<AiDtos.ExtractResponse> extract(AiDtos.ExtractRequest request) {
        return Optional.empty();
    }

    @Override
    public Optional<AiDtos.AskPlan> planAsk(AiDtos.AskPlanRequest request) {
        return Optional.empty();
    }

    @Override
    public Optional<AiDtos.ActivitiesResponse> readActivities(AiDtos.ActivitiesRequest request) {
        return Optional.empty();
    }
}
