package io.github.carpl2.tidebid.auction.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionAccountClientProperties;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

final class AuctionInternalServiceTokenFilter extends OncePerRequestFilter {

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    AuctionInternalServiceTokenFilter(AuctionAccountClientProperties properties, ObjectMapper objectMapper) {
        // A missing token makes every internal request fail closed; outbound clients still
        // use requiredInternalToken() and fail fast when they are actually needed.
        this.expectedToken = properties.internalToken().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = Collections.list(request.getHeaders(SecurityHeaders.INTERNAL_SERVICE_TOKEN));
        if (values.size() != 1 || !matches(values.getFirst())) {
            writeFailure(request, response, values.isEmpty() ? CommonErrorCode.UNAUTHENTICATED : CommonErrorCode.FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String supplied) {
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, actual);
    }

    private void writeFailure(HttpServletRequest request, HttpServletResponse response,
                              io.github.carpl2.tidebid.core.ErrorCode code) throws IOException {
        String traceId = traceId(request);
        response.setStatus(code.httpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(SecurityHeaders.TRACE_ID, traceId);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(code, traceId));
    }

    private static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (existing instanceof String value && TraceIds.isValid(value)) return value;
        String traceId = TraceIds.resolve(request.getHeader(SecurityHeaders.TRACE_ID));
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }
}
