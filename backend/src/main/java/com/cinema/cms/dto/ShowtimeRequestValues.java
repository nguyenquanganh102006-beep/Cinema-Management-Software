package com.cinema.cms.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Strict wire values: integer IDs and an ISO date-time string with offset. */
public final class ShowtimeRequestValues {
    private ShowtimeRequestValues() {}

    public static final class MovieId extends StdDeserializer<Long> {
        public MovieId() { super(Long.class); }
        @Override public Long deserialize(JsonParser p, DeserializationContext context) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) return (Long) context.handleUnexpectedToken(Long.class, p);
            return p.getLongValue();
        }
    }
    public static final class AuditoriumId extends StdDeserializer<Integer> {
        public AuditoriumId() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser p, DeserializationContext context) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) return (Integer) context.handleUnexpectedToken(Integer.class, p);
            return p.getIntValue();
        }
    }
    public static final class StartTime extends StdDeserializer<OffsetDateTime> {
        public StartTime() { super(OffsetDateTime.class); }
        @Override public OffsetDateTime deserialize(JsonParser p, DeserializationContext context) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_STRING) return (OffsetDateTime) context.handleUnexpectedToken(OffsetDateTime.class, p);
            try { return OffsetDateTime.parse(p.getText()); }
            catch (DateTimeParseException e) {
                return (OffsetDateTime) context.handleWeirdStringValue(OffsetDateTime.class, p.getText(), "Expected ISO date-time with offset");
            }
        }
    }
}
