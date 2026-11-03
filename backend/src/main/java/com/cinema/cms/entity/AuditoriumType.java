package com.cinema.cms.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;


public enum AuditoriumType {
    STANDARD_2D("STANDARD_2D"), THREE_D("3D"), IMAX("IMAX");

    private final String value;
    AuditoriumType(String value) { this.value = value; }

    @JsonValue public String value() { return value; }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AuditoriumType fromValue(String value) {
        for (AuditoriumType type : values()) if (type.value.equals(value)) return type;
        throw new IllegalArgumentException("Loại phòng không hợp lệ: " + value);
    }
}