package com.betteranalytics.crm.dedupe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the normalized match keys and the fuzzy name matcher. */
class MatchKeysTest {

    @Test
    void emailIsLowerCasedAndTrimmed() {
        assertThat(MatchKeys.email("  Pat.Lee@Acme.COM ")).isEqualTo("pat.lee@acme.com");
        assertThat(MatchKeys.email("not-an-email")).isNull();
        assertThat(MatchKeys.email("  ")).isNull();
        assertThat(MatchKeys.email(null)).isNull();
    }

    @Test
    void phoneBecomesE164OrDigits() {
        assertThat(MatchKeys.phone("+91 98765-43210")).isEqualTo("+919876543210");
        assertThat(MatchKeys.phone("0091 (98765) 43210")).isEqualTo("+919876543210");
        assertThat(MatchKeys.phone("ext 12")).isNull();
        assertThat(MatchKeys.phone("")).isNull();
    }

    @Test
    void domainComesFromWebsiteOrCompanyEmailButNotFreemail() {
        assertThat(MatchKeys.websiteDomain("https://WWW.Acme.com/about?x=1")).isEqualTo("acme.com");
        assertThat(MatchKeys.websiteDomain("acme.co.in")).isEqualTo("acme.co.in");
        assertThat(MatchKeys.websiteDomain("http://www.acme.com:8080")).isEqualTo("acme.com");
        assertThat(MatchKeys.emailDomain("pat@Acme.com")).isEqualTo("acme.com");
        assertThat(MatchKeys.emailDomain("pat@gmail.com")).isNull();
        assertThat(MatchKeys.websiteDomain("localhost")).isNull();
    }

    @Test
    void nameKeyStripsPunctuationAccentsAndCompanySuffixes() {
        assertThat(MatchKeys.nameKey("  José   O'Brien-Smith ")).isEqualTo("jose o brien smith");
        assertThat(MatchKeys.companyKey("Acme Pvt. Ltd.")).isEqualTo("acme");
        assertThat(MatchKeys.companyKey("The Acme Corporation")).isEqualTo("acme");
        assertThat(MatchKeys.companyKey("Ltd")).isEqualTo("ltd"); // never empty
        assertThat(MatchKeys.nameKey("!!!")).isNull();
    }

    @Test
    void phoneticKeyIsTheSurnameForPeople() {
        assertThat(MatchKeys.personPhonetic("jon smith")).isEqualTo(MatchKeys.personPhonetic("john smyth"));
        assertThat(MatchKeys.personPhonetic("j smith")).isEqualTo(MatchKeys.personPhonetic("john smith"));
        assertThat(MatchKeys.companyPhonetic("acme")).isNotNull();
    }

    @Test
    void jaroWinklerCatchesTypos() {
        assertThat(NameMatcher.similarity("jonathan smith", "jonathon smith")).isGreaterThanOrEqualTo(0.92);
        assertThat(NameMatcher.similarity("acme", "acme")).isEqualTo(1.0);
    }

    @Test
    void phoneticAndInitialsVariantsMatch() {
        assertThat(NameMatcher.similarity("catherine smith", "kathryn smyth")).isGreaterThan(0);
        assertThat(NameMatcher.similarity("j smith", "john smith")).isEqualTo(NameMatcher.INITIALS_SCORE);
        assertThat(NameMatcher.similarity("john smith", "j smith")).isEqualTo(NameMatcher.INITIALS_SCORE);
        assertThat(NameMatcher.similarity("j r smith", "john robert smith")).isEqualTo(NameMatcher.INITIALS_SCORE);
    }

    @Test
    void differentPeopleDoNotMatch() {
        assertThat(NameMatcher.similarity("john smith", "mary jones")).isZero();
        assertThat(NameMatcher.similarity("k smith", "john smith")).isZero();
        assertThat(NameMatcher.similarity("john smith", "john")).isLessThan(0.92);
        assertThat(NameMatcher.similarity(null, "john")).isZero();
        assertThat(NameMatcher.similarity("lead 1712345678", "lead 1712345679")).isZero();
        assertThat(NameMatcher.similarity("branch 12", "branch 12")).isEqualTo(1.0);
    }
}
