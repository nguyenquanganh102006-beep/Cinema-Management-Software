package com.cinema.cms.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorWriter {
    private final ObjectMapper mapper;
    private final ErrorResponses errors;
    public SecurityErrorWriter(ObjectMapper mapper, ErrorResponses errors) { this.mapper = mapper; this.errors = errors; }
    public void write(HttpServletRequest req, HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), errors.body(status, code, message, Map.of(), req.getRequestURI()));
    }
}
