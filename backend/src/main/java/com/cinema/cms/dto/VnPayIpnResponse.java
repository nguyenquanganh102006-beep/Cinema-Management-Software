package com.cinema.cms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact response contract expected by VNPay's IPN caller. */
public record VnPayIpnResponse(
        @JsonProperty("RspCode") String rspCode,
        @JsonProperty("Message") String message) {}
