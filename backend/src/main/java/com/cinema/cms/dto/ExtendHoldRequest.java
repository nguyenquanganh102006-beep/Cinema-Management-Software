package com.cinema.cms.dto;

import jakarta.validation.constraints.*;

public record ExtendHoldRequest(@NotNull @AssertTrue Boolean waitingForTransfer) {}
