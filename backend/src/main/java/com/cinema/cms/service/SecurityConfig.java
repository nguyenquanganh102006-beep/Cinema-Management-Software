package com.cinema.cms.service;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;
import org.springframework.http.server.ServerHttpResponse;
import java.io.IOException;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean FilterRegistrationBean<JwtAuthenticationFilter> disableServletFilter(JwtAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, JwtAuthenticationFilter jwt, SecurityErrorWriter errors,
            CorsConfigurationSource corsConfigurationSource,
            @Value("${springdoc.api-docs.enabled:false}") boolean docsEnabled) throws Exception {
        http.csrf(csrf -> csrf.disable()) // Authorization bearer only; no browser credential cookie.
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.POST,"/api/v1/auth/login","/api/v1/auth/register","/api/v1/auth/transfers/consume").permitAll();
                    if (docsEnabled) auth.requestMatchers("/swagger-ui/**","/swagger-ui.html","/v3/api-docs/**").permitAll();
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(e -> e
                    .authenticationEntryPoint((req,res,ex) -> errors.write(req,res,401,"AUTH_TOKEN_INVALID","Vui lòng đăng nhập"))
                    .accessDeniedHandler((req,res,ex) -> errors.write(req,res,403,"ACCESS_DENIED","Không đủ quyền truy cập")))
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource, SecurityErrorWriter errors) {
        var filter=new CorsFilter(corsConfigurationSource);
        filter.setCorsProcessor((config,req,res) -> new DefaultCorsProcessor() {
            @Override protected void rejectRequest(ServerHttpResponse response) throws IOException {
                errors.write(req,res,403,"CORS_ORIGIN_DENIED","Nguồn hoặc yêu cầu CORS không được phép");
            }
        }.processRequest(config,req,res));
        return filter;
    }
    @Bean FilterRegistrationBean<CorsFilter> disableServletCorsFilter(CorsFilter corsFilter) {
        var registration=new FilterRegistrationBean<>(corsFilter);registration.setEnabled(false);return registration;
    }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins}") String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::strip).toList());
        config.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type"));
        config.setAllowCredentials(false); config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", config); return source;
    }
}
