package com.mcplatform.domain.webauth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void rejectsTooShort() {
        assertThatThrownBy(() -> PasswordPolicy.validate("1234567")) // 7
                .isInstanceOf(InvalidPasswordException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(InvalidPasswordException.class);
    }

    @Test
    void acceptsMinLength() {
        assertThatCode(() -> PasswordPolicy.validate("12345678")).doesNotThrowAnyException(); // 8
    }

    @Test
    void acceptsMaxLength() {
        assertThatCode(() -> PasswordPolicy.validate("a".repeat(64))).doesNotThrowAnyException();
    }

    @Test
    void rejectsTooLong() {
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(65)))
                .isInstanceOf(InvalidPasswordException.class);
    }
}
