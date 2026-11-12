package com.s2p.core;

import com.s2p.core.ai.AiDtos;
import com.s2p.core.ai.HttpAiGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON contract with the Python AI service: snake_case both ways, failures become empty results. */
class HttpAiGatewayTest {
    private HttpServer server;

    private String start(int status, String body, AtomicReference<String> seen) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsSnakeCaseAndReadsTheModelsReply() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        String url = start(200, """
                {"intent":{"value":"ENQUIRY","evidence":"We need"},"lines":[{"product_text":"60mm coupling",
                 "qty":{"value":250,"evidence":"250 units"},"size_mm":{"value":60,"evidence":"60mm"},
                 "date":{"phrase":"by the 20th","iso":"2026-11-20","evidence":"by the 20th"}}],
                 "notes":[],"model":"gemini-3.8-flash","latency_ms":812,"unexpected":"ignored"}""", seen);
        HttpAiGateway g = new HttpAiGateway(url, Duration.ofSeconds(5));
        Optional<AiDtos.ExtractResponse> r = g.extract(new AiDtos.ExtractRequest("<m>", "2026-11-11T09:05+05:30",
                "2026-11-11", "a@b", "RFQ", "We need 250 units", List.of(), List.of(new AiDtos.CatalogItem("CPL-60-FX",
                "Flexible Jaw Coupling 60mm", "COUPLING", 60, "FLEXIBLE"))));
        assertTrue(r.isPresent());
        assertEquals(250, r.get().lines().get(0).qty().value());
        assertEquals("2026-11-20", r.get().lines().get(0).date().iso());
        assertEquals("gemini-3.8-flash", r.get().model());
        assertTrue(seen.get().contains("\"from_address\"") && seen.get().contains("\"size_mm\":60"), seen.get());
    }

    @Test
    void aFailingServiceGivesEmptyResultsAndIsSkippedAfterThreeFailures() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        String url = start(500, "{\"detail\":\"model error\"}", seen);
        HttpAiGateway g = new HttpAiGateway(url, Duration.ofSeconds(5));
        AiDtos.AskPlanRequest req = new AiDtos.AskPlanRequest("q", "SALES_REP", "2026-11-11", List.of(), List.of(),
                List.of(), List.of(), java.util.Map.of());
        for (int i = 0; i < 3; i++) {
            assertTrue(g.planAsk(req).isEmpty());
        }
        seen.set(null);
        assertTrue(g.planAsk(req).isEmpty());
        assertEquals(null, seen.get(), "the fourth call must not reach the service");
        assertEquals(3, g.telemetry().failures());
        assertTrue(g.telemetry().lastError().contains("HTTP 500"));
    }
}
