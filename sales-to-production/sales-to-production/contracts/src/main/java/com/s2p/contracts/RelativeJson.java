package com.s2p.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seed data is written relative to the business day ("D+9", "D-7T10:12") so the scenarios stay meaningful
 * whatever date the stack is started on. This loader resolves those tokens against an anchor date.
 */
public final class RelativeJson {
    private static final Pattern REL = Pattern.compile("^D([+-]\\d+)?(?:T(\\d{2}:\\d{2}))?$");

    private final ObjectMapper mapper;
    private final LocalDate anchor;

    public RelativeJson(LocalDate anchor) {
        this.anchor = anchor;
        this.mapper = mapper();
    }

    public static ObjectMapper mapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .build();
    }

    public <T> T load(String classpathResource, Class<T> type) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing seed resource " + classpathResource);
            }
            JsonNode resolved = resolve(mapper.readTree(in));
            return mapper.treeToValue(resolved, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode resolve(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                e.setValue(resolve(e.getValue()));
            }
            return obj;
        }
        if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, resolve(arr.get(i)));
            }
            return arr;
        }
        if (node.isTextual()) {
            Matcher m = REL.matcher(node.asText());
            if (m.matches()) {
                LocalDate d = anchor.plusDays(m.group(1) == null ? 0 : Integer.parseInt(m.group(1)));
                if (m.group(2) == null) {
                    return TextNode.valueOf(d.toString());
                }
                OffsetDateTime t = d.atTime(LocalTime.parse(m.group(2))).atZone(SimClock.ZONE).toOffsetDateTime();
                return TextNode.valueOf(t.toString());
            }
        }
        return node;
    }
}
