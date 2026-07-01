package com.mcplatform.api.rest;

import com.mcplatform.application.permission.InsufficientAuthorityException;
import com.mcplatform.application.permission.LastTopTierException;
import com.mcplatform.application.permission.port.DefaultRoleProtectedException;
import com.mcplatform.application.permission.port.RoleInheritanceCycleException;
import com.mcplatform.application.permission.port.RoleInheritedException;
import com.mcplatform.application.permission.port.RoleNameConflictException;
import com.mcplatform.application.permission.port.RoleNotFoundException;
import com.mcplatform.domain.permission.InvalidGrantException;
import com.mcplatform.domain.permission.RoleValidationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps permission-owned errors to HTTP status codes. Handles ONLY permission-owned exceptions —
 * {@code PermissionDeniedException} → 403 and {@code IllegalArgumentException} → 400 are already mapped
 * globally (by the punishment/economy advices); re-declaring them here would be an ambiguous mapping.
 */
@RestControllerAdvice
public class PermissionExceptionHandler {

    @ExceptionHandler(RoleNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(RoleNotFoundException e) {
        return Map.of("error", "role_not_found", "message", e.getMessage());
    }

    @ExceptionHandler(RoleNameConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> nameConflict(RoleNameConflictException e) {
        return Map.of("error", "role_name_conflict", "message", e.getMessage());
    }

    @ExceptionHandler(DefaultRoleProtectedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> defaultProtected(DefaultRoleProtectedException e) {
        return Map.of("error", "default_role_protected", "message", e.getMessage());
    }

    @ExceptionHandler(RoleInheritanceCycleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> inheritanceCycle(RoleInheritanceCycleException e) {
        return Map.of("error", "role_inheritance_cycle", "message", e.getMessage());
    }

    @ExceptionHandler(RoleInheritedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> inherited(RoleInheritedException e) {
        return Map.of("error", "role_inherited", "message", e.getMessage());
    }

    @ExceptionHandler({RoleValidationException.class, InvalidGrantException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> invalid(RuntimeException e) {
        return Map.of("error", "permission_invalid", "message", e.getMessage());
    }

    @ExceptionHandler(InsufficientAuthorityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> insufficientAuthority(InsufficientAuthorityException e) {
        return Map.of("error", "authority_ceiling", "message", e.getMessage());
    }

    @ExceptionHandler(LastTopTierException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> lastTopTier(LastTopTierException e) {
        return Map.of("error", "last_top_tier", "message", e.getMessage());
    }
}
