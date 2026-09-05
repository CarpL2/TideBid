package io.github.carpl2.tidebid.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void keepsPublicErrorInformation() {
        BusinessException exception = new BusinessException(
                CommonErrorCode.INVALID_ARGUMENT,
                "Username is invalid"
        );

        assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_ARGUMENT);
        assertThat(exception.getMessage()).isEqualTo("Username is invalid");
    }
}
