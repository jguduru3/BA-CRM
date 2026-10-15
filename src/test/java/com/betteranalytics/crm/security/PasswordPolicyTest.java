package com.betteranalytics.crm.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void shipsAboutAThousandCommonPasswords() {
        assertThat(PasswordPolicy.commonListSize()).isBetween(900, 1200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password123", "PASSWORD123", "Password2024!", "Welcome@123", "qwerty123456",
            "1q2w3e4r5t", "iloveyou12345", "Changeme123", "Admin@12345", "123456789a"})
    void rejectsCommonPasswordsCaseInsensitively(String pw) {
        String problem = PasswordPolicy.validate(pw, "someone@example.com");
        assertThat(problem).isNotNull();
    }

    @Test
    void commonReasonIsExplicit() {
        assertThat(PasswordPolicy.validate("Password2024!", "x@example.com")).contains("too common");
    }

    @Test
    void rejectsTheUsersOwnName() {
        assertThat(PasswordPolicy.validate("Priya-Sharma-2031", "ps@example.com", "Priya Sharma"))
                .contains("your name");
        assertThat(PasswordPolicy.validate("xxSHARMAxx9182", "ps@example.com", "Priya Sharma"))
                .contains("your name");
        // very short name parts (initials) don't block everything containing them
        assertThat(PasswordPolicy.validate("Granite-Lake-4417", "jo@example.com", "Jo Li")).isNull();
    }

    @Test
    void rejectsTheEmailName() {
        assertThat(PasswordPolicy.validate("ravikumar-8812x", "ravikumar@example.com")).contains("email name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Str0ngPassw0rd!", "BrandNewPassw0rd", "Granite-Lake-4417", "tq7#Vm2!pLx9"})
    void acceptsReasonablePasswords(String pw) {
        assertThat(PasswordPolicy.validate(pw, "someone@example.com", "Someone Else")).isNull();
    }

    @Test
    void keepsTheBasicRules() {
        assertThat(PasswordPolicy.validate("short1", null)).contains("at least");
        assertThat(PasswordPolicy.validate("onlyletterslong", null)).contains("letter and one number");
        assertThat(PasswordPolicy.validate("a1".repeat(40), null)).contains("at most");
    }
}
