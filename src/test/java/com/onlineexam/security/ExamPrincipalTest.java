package com.onlineexam.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.domain.Role;
import org.junit.jupiter.api.Test;

class ExamPrincipalTest {
    @Test
    void sameDatabaseAccountHasStableIdentityAcrossCredentialAndNameChanges() throws Exception {
        ExamPrincipal initial = new ExamPrincipal(7, "student_one", "Initial Name",
                "{bcrypt}first", Role.STUDENT, true);
        ExamPrincipal reloaded = new ExamPrincipal(7, "student_one", "Changed Name",
                "{bcrypt}second", Role.STUDENT, true);
        ExamPrincipal other = new ExamPrincipal(8, "student_two", "Other Name",
                "{bcrypt}third", Role.STUDENT, true);

        assertThat(initial).isEqualTo(reloaded).hasSameHashCodeAs(reloaded).isNotEqualTo(other);
        assertThat(initial.toString()).doesNotContain("bcrypt", "Initial Name");
        assertThat(new ObjectMapper().writeValueAsString(initial)).doesNotContain("bcrypt", "password");
    }
}
