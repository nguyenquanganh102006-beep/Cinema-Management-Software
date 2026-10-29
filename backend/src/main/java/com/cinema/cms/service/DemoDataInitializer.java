package com.cinema.cms.service;

import com.cinema.cms.entity.*;
import com.cinema.cms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class DemoDataInitializer implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final String password;
    private final String pin;
    public DemoDataInitializer(UserRepository users, PasswordEncoder passwords,
            @Value("${app.demo.password}") String password, @Value("${app.demo.manager-pin}") String pin) {
        this.users = users; this.passwords = passwords; this.password = password; this.pin = pin;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (password.length() < 8) throw new IllegalArgumentException("DEMO_PASSWORD must be at least 8 characters");
        AuthService.checkPasswordLength(password);
        if (!pin.matches("[0-9]{6,12}")) throw new IllegalArgumentException("DEMO_MANAGER_PIN must be 6..12 digits");
        seed("manager01", "0901000001", UserRole.MANAGER);
        seed("staff01", "0901000002", UserRole.STAFF);
        seed("customer01", "0901000003", UserRole.CUSTOMER);
    }
    private void seed(String username, String phone, UserRole role) {
        if (users.existsByUsername(username)) return;
        User user = new User(username, username, username + "@cinema.local", phone, passwords.encode(password), role);
        if (role == UserRole.MANAGER) user.setManagerPinHash(passwords.encode(pin));
        users.save(user);
    }
}
