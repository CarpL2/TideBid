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
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

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

    @Test
    void mapsMissingRequestHeaderToBadRequest() throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-12345678");
        Method method = HeaderProbe.class.getDeclaredMethod("handle", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingRequestHeader(
                new MissingRequestHeaderException("X-Request-Id", parameter),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON_INVALID_ARGUMENT");
        assertThat(response.getBody().message()).isEqualTo("Required request header is missing: X-Request-Id");
    }

    private static final class HeaderProbe {
        @SuppressWarnings("unused")
        void handle(String requestId) {
        }
    }
}
