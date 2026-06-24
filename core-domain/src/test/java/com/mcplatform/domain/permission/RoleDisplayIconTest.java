package com.mcplatform.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleDisplayIconTest {

    @Test
    void knownPrefixesAreValid() {
        assertThat(RoleDisplayIcon.isValid("material:DIAMOND_SWORD")).isTrue();
        assertThat(RoleDisplayIcon.isValid("head-texture:eyJ0ZXh0dXJlcyI6e30=")).isTrue();
        assertThat(RoleDisplayIcon.isValid("head-player:00000000-0000-0000-0000-000000000001")).isTrue();
    }

    @Test
    void nullMeansNoIconAndIsValid() {
        assertThatCode(() -> RoleDisplayIcon.validate(null)).doesNotThrowAnyException();
    }

    @Test
    void blankIsRejected() {
        assertThatThrownBy(() -> RoleDisplayIcon.validate("   "))
                .isInstanceOf(RoleValidationException.class);
    }

    @Test
    void unknownPrefixIsRejected() {
        assertThatThrownBy(() -> RoleDisplayIcon.validate("emoji:🔥"))
                .isInstanceOf(RoleValidationException.class);
    }

    @Test
    void missingColonOrEmptyPayloadIsRejected() {
        assertThatThrownBy(() -> RoleDisplayIcon.validate("DIAMOND_SWORD"))
                .isInstanceOf(RoleValidationException.class);
        assertThatThrownBy(() -> RoleDisplayIcon.validate("material:"))
                .isInstanceOf(RoleValidationException.class);
        assertThatThrownBy(() -> RoleDisplayIcon.validate(":payload"))
                .isInstanceOf(RoleValidationException.class);
    }

    @Test
    void roleConstructionRejectsInvalidIcon() {
        assertThatThrownBy(() -> new Role(RoleId.of(1), "VIP", "VIP", null, null, null, null, null,
                "bogus", 0, false, true, false))
                .isInstanceOf(RoleValidationException.class);
    }

    @Test
    void roleConstructionAcceptsValidIcon() {
        assertThatCode(() -> new Role(RoleId.of(1), "VIP", "VIP", null, null, null, null, null,
                "material:DIAMOND_SWORD", 0, false, true, false)).doesNotThrowAnyException();
    }
}
