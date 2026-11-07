package com.cinema.cms.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.List;

public record HoldSeatsRequest(
    @NotEmpty
        @Size(max = 100)
        @Schema(example = "[101,102]")
        @JsonDeserialize(contentUsing = SeatId.class)
        List<@NotNull @Positive Long> seatIds) {
  public static final class SeatId extends StdDeserializer<Long> {
    public SeatId() {
      super(Long.class);
    }

    @Override
    public Long deserialize(JsonParser p, DeserializationContext context) throws IOException {
      if (p.currentToken() != JsonToken.VALUE_NUMBER_INT)
        return (Long) context.handleUnexpectedToken(Long.class, p);
      return p.getLongValue();
    }
  }
}
