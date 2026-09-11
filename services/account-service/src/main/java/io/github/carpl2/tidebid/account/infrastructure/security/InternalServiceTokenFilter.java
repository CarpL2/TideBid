package io.github.carpl2.tidebid.account.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.core.ErrorCode;
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
import java.util.List;

final class InternalServiceTokenFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    InternalServiceTokenFilter(InternalServiceTokenProperties properties, ObjectMapper objectMapper) {
        this.expectedToken = properties.token().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        List<String> suppliedTokens = Collections.list(
                request.getHeaders(SecurityHeaders.INTERNAL_SERVICE_TOKEN)
        );
        if (suppliedTokens.isEmpty()) {
            writeFailure(request, response, CommonErrorCode.UNAUTHENTICATED);
            return;
        }
        if (suppliedTokens.size() != 1 || !matches(suppliedTokens.getFirst())) {
            writeFailure(request, response, CommonErrorCode.FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String suppliedToken) {
        byte[] suppliedBytes = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, suppliedBytes);
    }

    private void writeFailure(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode)
            throws IOException {
        String traceId = traceId(request);
        response.setStatus(errorCode.httpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(SecurityHeaders.TRACE_ID, traceId);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorCode, traceId));
    }

    private static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (existing instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        String traceId = TraceIds.resolve(request.getHeader(SecurityHeaders.TRACE_ID));
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }
}
