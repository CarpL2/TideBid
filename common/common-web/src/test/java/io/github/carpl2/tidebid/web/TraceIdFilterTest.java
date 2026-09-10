package io.github.carpl2.tidebid.web;

import io.github.carpl2.tidebid.security.SecurityHeaders;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void usesSafeIncomingTraceIdForResponseAndLogging() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(SecurityHeaders.TRACE_ID, "client-trace-1234");

        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo("client-trace-1234");
            assertThat(servletRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE))
                    .isEqualTo("client-trace-1234");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(SecurityHeaders.TRACE_ID)).isEqualTo("client-trace-1234");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(SecurityHeaders.TRACE_ID, "bad trace\nforged");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertThat(response.getHeader(SecurityHeaders.TRACE_ID)).matches("[a-f0-9]{32}");
    }

    @Test
    void logsCompletionWithoutHeadersOrQueryString(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setQueryString("token=must-not-be-logged");
        request.addHeader(SecurityHeaders.TRACE_ID, "service-trace-1234");
        request.addHeader(SecurityHeaders.AUTHORIZATION, "Bearer must-not-be-logged");

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(204));

        assertThat(output.getAll())
                .contains("Request completed traceId=service-trace-1234"
                        + " method=GET path=/api/users/me status=204")
                .doesNotContain("must-not-be-logged");
    }
}
