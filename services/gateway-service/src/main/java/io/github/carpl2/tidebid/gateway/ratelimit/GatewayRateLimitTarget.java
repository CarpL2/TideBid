package io.github.carpl2.tidebid.gateway.ratelimit;

enum GatewayRateLimitTarget {
    REGISTRATION("registration", "/api/auth/register"),
    LOGIN("login", "/api/auth/login");

    private final String keySegment;
    private final String path;

    GatewayRateLimitTarget(String keySegment, String path) {
        this.keySegment = keySegment;
        this.path = path;
    }

    String keySegment() {
        return keySegment;
    }

    String path() {
        return path;
    }
}
