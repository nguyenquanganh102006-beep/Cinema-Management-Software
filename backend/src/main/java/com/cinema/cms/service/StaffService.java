package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.AccountStatus;
import com.cinema.cms.entity.User;
import com.cinema.cms.entity.UserRole;
import com.cinema.cms.repository.UserRepository;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffService {
    static final int MAX_PAGE_SIZE = 100;
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public StaffService(UserRepository users, PasswordEncoder passwords) { this.users = users; this.passwords = passwords; }

    @Transactional(readOnly = true)
    public PageResponse<StaffResponse> list(String keyword, String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(400, "VALIDATION_ERROR", "page phải từ 0 trở lên và size từ 1 đến " + MAX_PAGE_SIZE);
        }
        Specification<User> spec = (root, query, cb) -> cb.equal(root.get("role"), UserRole.STAFF);
        if (keyword != null && !keyword.isBlank()) spec = spec.and(matchesKeyword(keyword.strip()));
        if (status != null && !status.isBlank()) spec = spec.and(hasStatus(parseStatus(status)));
        var sort = Sort.by(Sort.Order.asc("fullName"), Sort.Order.asc("id"));
        return PageResponse.from(users.findAll(spec, PageRequest.of(page, size, sort)), StaffResponse::from);
    }

    @Transactional(readOnly = true)
    public StaffResponse get(Long id) { return StaffResponse.from(find(id)); }

    @Transactional
    public StaffResponse create(CreateStaffRequest r) {
        AuthService.checkPasswordLength(r.password());
        if (users.existsByUsername(r.username())) throw conflict("USERNAME_ALREADY_EXISTS", "Tên đăng nhập đã tồn tại");
        if (users.existsByEmail(r.email())) throw conflict("EMAIL_ALREADY_EXISTS", "Email đã được sử dụng");
        if (users.existsByPhone(r.phone())) throw conflict("PHONE_ALREADY_EXISTS", "Số điện thoại đã được sử dụng");
        User staff = new User(r.username(), r.fullName(), r.email(), r.phone(), passwords.encode(r.password()), UserRole.STAFF);
        return StaffResponse.from(users.saveAndFlush(staff));
    }

    @Transactional
    public StaffResponse update(Long id, UpdateStaffRequest r) {
        User staff = find(id);
        if (users.existsByEmailAndIdNot(r.email(), id)) throw conflict("EMAIL_ALREADY_EXISTS", "Email đã được sử dụng");
        if (users.existsByPhoneAndIdNot(r.phone(), id)) throw conflict("PHONE_ALREADY_EXISTS", "Số điện thoại đã được sử dụng");
        staff.updateProfile(r.fullName(), r.email(), r.phone());
        return StaffResponse.from(users.saveAndFlush(staff));
    }


    @Transactional
    public StaffResponse changeStatus(Long id, StaffStatusRequest r) {
        User staff = find(id);
        AccountStatus target = r.active() ? AccountStatus.ACTIVE : AccountStatus.INACTIVE;
        if (staff.getStatus() != target) {
            staff.changeStatus(target);
            if (target != AccountStatus.ACTIVE) staff.invalidateSessions();
        }
        return StaffResponse.from(users.saveAndFlush(staff));
    }

    private User find(Long id) {
        return users.findByIdAndRole(id, UserRole.STAFF).orElseThrow(() -> new ApiException(404, "STAFF_NOT_FOUND", "Không tìm thấy nhân viên"));
    }
    private static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
    private static AccountStatus parseStatus(String value) {
        try { return AccountStatus.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ApiException(400, "VALIDATION_ERROR", "status phải là ACTIVE, INACTIVE hoặc LOCKED"); }
    }
    private static Specification<User> hasStatus(AccountStatus status) { return (root, query, cb) -> cb.equal(root.get("status"), status); }
    private static Specification<User> matchesKeyword(String keyword) {
        String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(cb.like(cb.lower(root.get("fullName")), pattern, '\\'), cb.like(cb.lower(root.get("username")), pattern, '\\'));
    }
    private static String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}