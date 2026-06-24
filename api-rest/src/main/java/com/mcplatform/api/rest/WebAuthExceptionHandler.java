package com.mcplatform.api.rest;

import com.mcplatform.application.webauth.port.TokenInvalidException;
import com.mcplatform.application.webauth.port.WebAccountConflictException;
import com.mcplatform.application.webauth.port.WebAccountExistsException;
import com.mcplatform.application.webauth.port.WebAccountMissingException;
import com.mcplatform.application.webauth.port.WebAuthCooldownException;
import com.mcplatform.domain.webauth.InvalidPasswordException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps web-auth-specific errors to HTTP status codes. Handles ONLY web-auth-owned exceptions —
 * {@code IllegalArgumentException} → 400 is already mapped globally (economy/punishment advices);
 * re-declaring it here would be an ambiguous-mapping conflict. There is no 403/401 in this slice
 * (no permission/auth gate). The 410 for token invalidity is deliberately uniform (FR-019/SC-005).
 */
@RestControllerAdvice
public class WebAuthExceptionHandler {

    @ExceptionHandler(WebAccountExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> exists(WebAccountExistsException e) {
        return Map.of("error", "web_account_exists", "message", e.getMessage());
    }

    @ExceptionHandler(WebAccountMissingException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> missing(WebAccountMissingException e) {
        return Map.of("error", "web_account_missing", "message", e.getMessage());
    }

    @ExceptionHandler(WebAccountConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(WebAccountConflictException e) {
        return Map.of("error", "web_account_conflict", "message", e.getMessage());
    }

    @ExceptionHandler(TokenInvalidException.class)
    @ResponseStatus(HttpStatus.GONE)
    public Map<String, String> tokenInvalid(TokenInvalidException e) {
        return Map.of("error", "web_auth_token_invalid", "message", e.getMessage());
    }

    @ExceptionHandler(InvalidPasswordException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> password(InvalidPasswordException e) {
        return Map.of("error", "password_invalid", "message", e.getMessage());
    }

    @ExceptionHandler(WebAuthCooldownException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> cooldown(WebAuthCooldownException e) {
        return Map.of("error", "web_auth_cooldown", "message", e.getMessage());
    }
}
