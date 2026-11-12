package com.s2p.core.ai;

import java.util.Optional;

/**
 * The core's only way to a language model, and the only place the core understands human language: customer mail,
 * questions in plain words, and CRM notes. The model reads; it never decides a date, a quantity or a price, and it has
 * no way to change anything: every call returns data the core checks before it is used. Every method returns empty
 * when the model cannot be reached; callers then say so rather than guess.
 */
public interface AiGateway {
    boolean enabled();

    /** Where the model is reached, for the system's report on itself. */
    String describeConfig();

    AiTelemetry telemetry();

    Optional<AiDtos.ExtractResponse> extract(AiDtos.ExtractRequest request);

    Optional<AiDtos.AskPlan> planAsk(AiDtos.AskPlanRequest request);

    Optional<AiDtos.ActivitiesResponse> readActivities(AiDtos.ActivitiesRequest request);

    static AiGateway disabled() {
        return new DisabledAiGateway();
    }
}
