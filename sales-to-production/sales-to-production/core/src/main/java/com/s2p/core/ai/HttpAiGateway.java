package com.s2p.core.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Calls the Python AI service (ai-service/, FastAPI, Gemini behind it) over HTTP with the JDK client, so the core
 * keeps no framework dependency. Any failure, timeout or malformed reply becomes an empty result and is counted.
 */
public final class HttpAiGateway implements AiGateway {
    private final String base;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json;
    private final AiTelemetry telemetry = new AiTelemetry();

    public HttpAiGateway(String baseUrl, Duration timeout) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        this.json = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String describeConfig() {
        return "language model via the AI service at " + base + " (timeout " + timeout.toMillis() + " ms; when it "
                + "fails, mail waits unread and the answer says the question could not be read)";
    }

    @Override
    public AiTelemetry telemetry() {
        return telemetry;
    }

    @Override
    public Optional<AiDtos.ExtractResponse> extract(AiDtos.ExtractRequest request) {
        return post("/v1/extract", request, AiDtos.ExtractResponse.class);
    }

    @Override
    public Optional<AiDtos.AskPlan> planAsk(AiDtos.AskPlanRequest request) {
        return post("/v1/ask/plan", request, AiDtos.AskPlan.class);
    }

    @Override
    public Optional<AiDtos.ActivitiesResponse> readActivities(AiDtos.ActivitiesRequest request) {
        return post("/v1/activities", request, AiDtos.ActivitiesResponse.class);
    }

    private <T> Optional<T> post(String path, Object body, Class<T> type) {
        if (telemetry.circuitOpen()) {
            return Optional.empty();
        }
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + res.statusCode() + " " + abbreviate(res.body()));
            }
            T out = json.readValue(res.body(), type);
            telemetry.success(modelOf(out), (System.nanoTime() - t0) / 1_000_000);
            return Optional.of(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            telemetry.failure(path, "interrupted");
        } catch (IOException | RuntimeException e) {
            telemetry.failure(path, e.getClass().getSimpleName() + (e.getMessage() == null ? " (service not reachable)"
                    : ": " + abbreviate(e.getMessage())));
        }
        return Optional.empty();
    }

    private static String modelOf(Object o) {
        return switch (o) {
            case AiDtos.ExtractResponse r -> r.model();
            case AiDtos.AskPlan p -> p.model();
            case AiDtos.ActivitiesResponse a -> a.model();
            default -> null;
        };
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
