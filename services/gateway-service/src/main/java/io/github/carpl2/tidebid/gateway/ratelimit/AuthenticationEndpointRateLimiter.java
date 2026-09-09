package io.github.carpl2.tidebid.gateway.ratelimit;

import reactor.core.publisher.Mono;

interface AuthenticationEndpointRateLimiter {

    Mono<RateLimitDecision> acquire(GatewayRateLimitTarget target, String clientIdentifier);
}
