package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final TokenRevocationService revocations;
    private final String dummyHash;
    public AuthService(UserRepository users, PasswordEncoder passwords, JwtService jwt, TokenRevocationService revocations) {
        this.users = users; this.passwords = passwords; this.jwt = jwt; this.revocations = revocations;
        this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }
    @Transactional
    public UserResponse register(RegisterRequest req) {
        checkPasswordLength(req.password());
        var user = new User(req.username(), req.fullName(), req.email(), req.phone(), passwords.encode(req.password()), UserRole.CUSTOMER);
        return UserResponse.from(users.saveAndFlush(user));
    }
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        String identity = req.username().strip().toLowerCase(Locale.ROOT);
        User user = users.findByUsernameOrEmail(identity, identity).orElse(null);
        boolean lengthOk = req.password().getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matched = passwords.matches(lengthOk ? req.password() : "invalid-long-input", user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !lengthOk || !matched) {
            log.info("Authentication rejected: invalid credentials");
            throw new ApiException(401,"AUTH_INVALID_CREDENTIALS","Tên đăng nhập hoặc mật khẩu không đúng");
        }
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(401,"ACCOUNT_" + user.getStatus().name(),"Tài khoản đã bị khóa hoặc ngừng hoạt động");
        }
        return new LoginResponse(jwt.issue(user), jwt.ttlSeconds(), UserResponse.from(user));
    }
    @Transactional(readOnly = true)
    public UserResponse me(SessionPrincipal principal) {
        return UserResponse.from(users.findById(principal.userId()).orElseThrow(() -> new ApiException(401,"AUTH_TOKEN_INVALID","Tài khoản không tồn tại")));
    }
    public void logout(SessionPrincipal principal) { revocations.revoke(principal.tokenId(), principal.expiresAt()); }
    public static void checkPasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new ApiException(400,"VALIDATION_ERROR","Mật khẩu không được vượt quá 72 byte UTF-8");
    }
}
