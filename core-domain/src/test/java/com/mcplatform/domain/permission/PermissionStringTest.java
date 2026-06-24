package com.mcplatform.domain.permission;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PermissionStringTest {

    @Test
    void acceptsExactWildcardAndPrefix() {
        assertThatCode(() -> PermissionString.validate("*")).doesNotThrowAnyException();
        assertThatCode(() -> PermissionString.validate("report.view")).doesNotThrowAnyException();
        assertThatCode(() -> PermissionString.validate("report.*")).doesNotThrowAnyException();
        assertThatCode(() -> PermissionString.validate("permission.role.create")).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankWhitespaceNegationAndEmptySegments() {
        assertThatThrownBy(() -> PermissionString.validate(null)).isInstanceOf(RoleValidationException.class);
        assertThatThrownBy(() -> PermissionString.validate("  ")).isInstanceOf(RoleValidationException.class);
        assertThatThrownBy(() -> PermissionString.validate("home set")).isInstanceOf(RoleValidationException.class);
        assertThatThrownBy(() -> PermissionString.validate("-home.set")).isInstanceOf(RoleValidationException.class);
        assertThatThrownBy(() -> PermissionString.validate("home..set")).isInstanceOf(RoleValidationException.class);
        assertThatThrownBy(() -> PermissionString.validate(".home")).isInstanceOf(RoleValidationException.class);
        assertThatThrownBy(() -> PermissionString.validate("home.")).isInstanceOf(RoleValidationException.class);
    }
}
