package com.mcplatform.api.rest;

import com.mcplatform.application.webauth.SessionTokens;
import com.mcplatform.application.webauth.WebSessionService;
import com.mcplatform.application.webauth.port.RefreshTokenInvalidException;
import com.mcplatform.protocol.webauth.LoginRequest;
import com.mcplatform.protocol.webauth.TokenPairResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Web session endpoints (login / refresh / logout). Thin transport layer: it delegates all logic to
 * {@link WebSessionService} and only handles the HTTP/cookie concern — the access token goes in the JSON
 * body, the raw refresh token rides exclusively in an httpOnly/Secure/SameSite=Strict cookie (plan R5).
 * Refresh/logout require a {@code X-Refresh} header (CSRF guard on the cookie-bearing endpoints). This
 * controller has no permission logic — authorization on protected feature endpoints is the
 * PermissionResolver's job (Constitution §12).
 */
@RestController
public class WebSessionController {

    private final WebSessionService sessions;
    private final String cookieName;
    private final String cookiePath;
    private final Duration refreshTtl;

    public WebSessionController(WebSessionService sessions,
            @Value("${mcplatform.webauth.refresh-cookie.name:mcweb_refresh}") String cookieName,
            @Value("${mcplatform.webauth.refresh-cookie.path:/api/web-auth/session}") String cookiePath,
            @Value("${mcplatform.webauth.refresh-ttl-days:30}") long refreshTtlDays) {
        this.sessions = sessions;
        this.cookieName = cookieName;
        this.cookiePath = cookiePath;
        this.refreshTtl = Duration.ofDays(refreshTtlDays);
    }

    /** Login with MC name + password. 401 (uniform) on any credential failure. */
    @PostMapping("/api/web-auth/login")
    public ResponseEntity<TokenPairResponse> login(@RequestBody LoginRequest request) {
        SessionTokens tokens = sessions.login(request.username(), request.password());
        return withRefreshCookie(tokens, refreshTtl);
    }

    /** Refresh: rotate the refresh cookie → new access + new cookie. 401 invalid / 401 reuse / 403 no X-Refresh. */
    @PostMapping("/api/web-auth/session/refresh")
    public ResponseEntity<TokenPairResponse> refresh(HttpServletRequest httpRequest,
            @RequestHeader(name = "X-Refresh", required = false) String csrfGuard) {
        requireCsrfHeader(csrfGuard);
        String refresh = readRefreshCookie(httpRequest);
        if (refresh == null || refresh.isBlank()) {
            throw new RefreshTokenInvalidException("missing refresh token");
        }
        SessionTokens tokens = sessions.refresh(refresh);
        return withRefreshCookie(tokens, refreshTtl);
    }

    /** Logout: invalidate the presented refresh token. Idempotent (204), clears the cookie. 403 no X-Refresh. */
    @PostMapping("/api/web-auth/session/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest,
            @RequestHeader(name = "X-Refresh", required = false) String csrfGuard) {
        requireCsrfHeader(csrfGuard);
        sessions.logout(readRefreshCookie(httpRequest));
        ResponseCookie cleared = baseCookie("").maxAge(0).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }

    private ResponseEntity<TokenPairResponse> withRefreshCookie(SessionTokens tokens, Duration maxAge) {
        ResponseCookie cookie = baseCookie(tokens.refreshRawToken()).maxAge(maxAge).build();
        TokenPairResponse body = new TokenPairResponse(
                tokens.accessToken(),
                tokens.accessExpiresAt().toEpochMilli(),
                tokens.refreshExpiresAt().toEpochMilli());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(cookiePath);
    }

    private String readRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void requireCsrfHeader(String csrfGuard) {
        if (csrfGuard == null || csrfGuard.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing X-Refresh header");
        }
    }
}
