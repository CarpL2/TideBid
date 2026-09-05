package io.github.carpl2.tidebid.web;

import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsBusinessExceptionWithoutLosingTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-12345678");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
                new BusinessException(CommonErrorCode.CONFLICT, "Username already exists"),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON_CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("Username already exists");
        assertThat(response.getBody().traceId()).isEqualTo("trace-12345678");
    }

    @Test
    void hidesUnexpectedExceptionDetails(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-12345678");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(
                new IllegalStateException("database password leaked"),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("An internal error occurred");
        assertThat(response.getBody().message()).doesNotContain("password");
        assertThat(output.getAll()).doesNotContain("database password leaked");
    }
}
