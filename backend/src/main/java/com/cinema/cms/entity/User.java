package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id") private Long id;
    @Column(nullable = false, length = 50) private String username;
    @Column(name = "full_name", nullable = false, length = 100) private String fullName;
    @Column(nullable = false, length = 150) private String email;
    @Column(nullable = false, length = 20) private String phone;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "manager_pin_hash") private String managerPinHash;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "user_role") private UserRole role;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "account_status") private AccountStatus status;
    @Column(name = "security_version", nullable = false) private int securityVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected User() {}
    public User(String username, String fullName, String email, String phone, String passwordHash, UserRole role) {
        this.username = username; this.fullName = fullName; this.email = email; this.phone = phone;
        this.passwordHash = passwordHash; this.role = role; this.status = AccountStatus.ACTIVE;
    }
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getPasswordHash() { return passwordHash; }
    public UserRole getRole() { return role; }
    public AccountStatus getStatus() { return status; }
    public int getSecurityVersion() { return securityVersion; }
    public void setManagerPinHash(String hash) { managerPinHash = hash; }
}
