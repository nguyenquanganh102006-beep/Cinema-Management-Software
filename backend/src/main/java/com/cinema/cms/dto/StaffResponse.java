package com.cinema.cms.dto;

import com.cinema.cms.entity.AccountStatus;
import com.cinema.cms.entity.User;
import java.time.Instant;

public record StaffResponse(Long id, String username, String fullName, String email, String phone,
                            AccountStatus status, Instant createdAt) {
    public static StaffResponse from(User u) {
        return new StaffResponse(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getPhone(), u.getStatus(), u.getCreatedAt());
    }
}