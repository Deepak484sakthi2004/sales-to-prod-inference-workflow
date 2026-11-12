package com.s2p.core.persistence;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.s2p.core.governance.ActionPayload;

import java.math.BigDecimal;

/**
 * How records become documents. Money (BigDecimal) is written as a string so a price of 1234.50 comes back exactly
 * as 1234.50 from any backend; dates keep their offset; {@link ActionPayload} carries a type name because it is a
 * sealed interface. Every new payload record is picked up automatically from the permitted subclasses.
 */
public final class StoreCodec {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    private interface PayloadTyping {
    }

    private static final ObjectMapper MAPPER = build();

    private StoreCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper build() {
        JsonMapper m = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .addMixIn(ActionPayload.class, PayloadTyping.class)
                .build();
        m.configOverride(BigDecimal.class).setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING));
        for (Class<?> sub : ActionPayload.class.getPermittedSubclasses()) {
            m.registerSubtypes(new NamedType(sub, sub.getSimpleName()));
        }
        return m;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new StoreException("Cannot serialise " + value.getClass().getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T read(String json, JavaType type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new StoreException("Cannot read stored " + type.getRawClass().getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }
}
