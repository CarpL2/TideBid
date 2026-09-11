package io.github.carpl2.tidebid.security;

/**
 * Header names used at the trusted gateway-to-service boundary.
 */
public final class SecurityHeaders {

    public static final String AUTHORIZATION = "Authorization";
    public static final String TRACE_ID = "X-Trace-Id";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String INTERNAL_USER_ID = "X-TideBid-User-Id";
    public static final String INTERNAL_USER_ROLES = "X-TideBid-User-Roles";
    public static final String INTERNAL_SERVICE_TOKEN = "X-TideBid-Internal-Token";
    public static final String BEARER_PREFIX = "Bearer ";

    private SecurityHeaders() {
    }
}
