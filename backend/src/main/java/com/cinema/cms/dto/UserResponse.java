package com.cinema.cms.dto;
import com.cinema.cms.entity.User;
import com.cinema.cms.entity.UserRole;
public record UserResponse(Long id, String username, String fullName, String email, String phone, UserRole role) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }
}
