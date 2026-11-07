package com.cinema.cms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Cash handed over at the POS counter. The backend owns the amount actually charged. */
public record CashPaymentRequest(
        @NotNull(message = "Số tiền khách đưa là bắt buộc")
                @DecimalMin(value = "0.00", message = "Số tiền khách đưa không hợp lệ")
                @Digits(integer = 10, fraction = 2, message = "Số tiền khách đưa không hợp lệ")
                BigDecimal cashReceived,
        Boolean ageDocumentChecked) {}
