package com.cinema.cms.service;

import com.cinema.cms.entity.AccountStatus;
import com.cinema.cms.entity.User;
import com.cinema.cms.repository.UserRepository;
import io.jsonwebtoken.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.dao.DataAccessException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserRepository users;
    private final TokenRevocationService revocations;
    private final SecurityErrorWriter errors;
    public JwtAuthenticationFilter(JwtService jwt, UserRepository users, TokenRevocationService revocations, SecurityErrorWriter errors) {
        this.jwt = jwt; this.users = users; this.revocations = revocations; this.errors = errors;
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null) { chain.doFilter(req, res); return; }
        try {
            if (!header.startsWith("Bearer ") || header.length() <= 7) throw new MalformedJwtException("Invalid bearer header");
            Claims c = jwt.parse(header.substring(7));
            User user = users.findById(Long.valueOf(c.getSubject())).orElseThrow(() -> new MalformedJwtException("Unknown user"));
            if (revocations.isRevoked(c.getId()) || user.getStatus() != AccountStatus.ACTIVE
                    || user.getSecurityVersion() != ((Number)c.get("sv")).intValue()) throw new MalformedJwtException("Invalid session");
            var principal = new SessionPrincipal(user.getId(), user.getUsername(), user.getRole(), c.getId(), c.getExpiration().toInstant());
            var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (ExpiredJwtException e) {
            errors.write(req, res, 401,"AUTH_TOKEN_EXPIRED","Phiên đăng nhập đã hết hạn"); return;
        } catch (JwtException | IllegalArgumentException e) {
            errors.write(req, res, 401,"AUTH_TOKEN_INVALID","Phiên đăng nhập không hợp lệ"); return;
        } catch (DataAccessException e) {
            errors.write(req, res, 503,"SERVICE_UNAVAILABLE","Không thể xác thực lúc này, vui lòng thử lại"); return;
        }
        chain.doFilter(req, res);
    }
}
