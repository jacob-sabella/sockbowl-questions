package com.soulsoftworks.sockbowlquestions.api;

import graphql.ErrorClassification;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shared {@link SockbowlErrorType} contract: M3 and M4 reference these
 * values from parallel branches and ng matches on the names, so the set is fixed.
 */
class SockbowlErrorTypeTest {

    @Test
    void declares_every_value_m3_and_m4_rely_on() {
        assertThat(Arrays.stream(SockbowlErrorType.values()).map(Enum::name))
                .containsExactlyInAnyOrder(
                        "CONFLICT", "VALIDATION_FAILED", "PAYLOAD_TOO_LARGE",
                        "RATE_LIMITED", "QUOTA_EXCEEDED", "BANNED", "LIMITER_UNAVAILABLE");
    }

    @Test
    void is_a_graphql_error_classification() {
        ErrorClassification c = SockbowlErrorType.RATE_LIMITED;
        assertThat(c.toSpecification(null)).isEqualTo("RATE_LIMITED");
    }
}
