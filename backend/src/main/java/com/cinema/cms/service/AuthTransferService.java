package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.UserRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthTransferService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final JwtService jwt;
    private final TokenRevocationService revocations;
    private final Clock clock;
    private final Map<UserRole,String> portals;
    private final SecureRandom random = new SecureRandom();
    public AuthTransferService(JdbcTemplate jdbc, UserRepository users, JwtService jwt,
            TokenRevocationService revocations, Clock clock,
            @Value("${app.portals.manager:http://localhost:3001}") String manager,
            @Value("${app.portals.staff:http://localhost:3002}") String staff,
            @Value("${app.portals.customer:http://localhost:3003}") String customer) {
        this.jdbc=jdbc;this.users=users;this.jwt=jwt;this.revocations=revocations;this.clock=clock;
        portals=Map.of(UserRole.MANAGER,validatePortal(manager),UserRole.STAFF,validatePortal(staff),UserRole.CUSTOMER,validatePortal(customer));
    }
    private String validatePortal(String url) {
        URI uri=URI.create(url);
        boolean local=Set.of("localhost","127.0.0.1","[::1]").contains(Objects.toString(uri.getHost(),""));
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("Portal URLs require HTTPS, except loopback development URLs");
        }
        return url;
    }
    @Transactional
    public AuthTransferResponse create(SessionPrincipal session) {
        User user=users.findById(session.userId()).orElseThrow(this::invalid);
        if(user.getStatus()!=AccountStatus.ACTIVE || revocations.isRevoked(session.tokenId())) throw invalid();
        byte[] bytes=new byte[32];random.nextBytes(bytes);String code=HexFormat.of().formatHex(bytes);
        jdbc.update("insert into auth_transfers(code_hash,user_id,target_role,security_version,expires_at) values (?,?,?::user_role,?,?)",
                hash(code),user.getId(),user.getRole().name(),user.getSecurityVersion(),Timestamp.from(clock.instant().plusSeconds(30)));
        revocations.revoke(session.tokenId(),session.expiresAt());
        return new AuthTransferResponse(portals.get(user.getRole())+"#auth-transfer="+code);
    }
    @Transactional
    public LoginResponse consume(AuthTransferRequest request) {
        var rows=jdbc.queryForList("select user_id,target_role,security_version,expires_at from auth_transfers where code_hash=? for update",hash(request.code()));
        if(rows.isEmpty()) throw invalid();
        var row=rows.getFirst();
        if(!((Timestamp)row.get("expires_at")).toInstant().isAfter(clock.instant()) || !row.get("target_role").toString().equals(request.role().name())) throw invalid();
        User user=users.findById(((Number)row.get("user_id")).longValue()).orElseThrow(this::invalid);
        if(user.getStatus()!=AccountStatus.ACTIVE || user.getRole()!=request.role()
                || user.getSecurityVersion()!=((Number)row.get("security_version")).intValue()) throw invalid();
        jdbc.update("delete from auth_transfers where code_hash=?",hash(request.code()));
        return new LoginResponse(jwt.issue(user),jwt.ttlSeconds(),UserResponse.from(user));
    }
    private ApiException invalid() { return new ApiException(401,"AUTH_TRANSFER_INVALID","Mã chuyển phiên không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại."); }
    private String hash(String code) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    @Scheduled(fixedDelayString="${app.jwt.cleanup-delay-ms:600000}")
    public void cleanup() { jdbc.update("delete from auth_transfers where expires_at<=?",Timestamp.from(clock.instant())); }
}
