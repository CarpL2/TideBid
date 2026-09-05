package io.github.carpl2.tidebid.web;

import io.github.carpl2.tidebid.security.SecurityHeaders;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

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
}
