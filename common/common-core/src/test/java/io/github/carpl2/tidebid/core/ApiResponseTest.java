package io.github.carpl2.tidebid.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void createsSuccessEnvelope() {
        ApiResponse<String> response = ApiResponse.success("payload", "trace-12345678");

        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.message()).isEqualTo("OK");
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.traceId()).isEqualTo("trace-12345678");
    }

    @Test
    void createsFailureEnvelopeWithoutData() {
        ApiResponse<Void> response = ApiResponse.failure(
                CommonErrorCode.CONFLICT,
                "Username already exists",
                "trace-12345678"
        );

        assertThat(response.code()).isEqualTo("COMMON_CONFLICT");
        assertThat(response.message()).isEqualTo("Username already exists");
        assertThat(response.data()).isNull();
    }
}
