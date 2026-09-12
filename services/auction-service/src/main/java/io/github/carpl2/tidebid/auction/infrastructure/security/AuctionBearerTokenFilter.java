package io.github.carpl2.tidebid.auction.infrastructure.security;

import io.github.carpl2.tidebid.security.AuthenticatedUser;
import io.github.carpl2.tidebid.security.InvalidAccessTokenException;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtClaims;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

final class AuctionBearerTokenFilter extends OncePerRequestFilter {

    private final JwtAccessTokenVerifier tokenVerifier;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    AuctionBearerTokenFilter(
            JwtAccessTokenVerifier tokenVerifier,
            AuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.tokenVerifier = tokenVerifier;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String authorization = authorizationHeader(request);
            if (authorization != null) {
                authenticate(request, bearerToken(authorization));
            }
        } catch (InvalidAccessTokenException exception) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("Access token is invalid")
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String encodedToken) {
        JwtClaims claims = tokenVerifier.verify(encodedToken);
        AuthenticatedUser principal = new AuthenticatedUser(
                claims.userId(),
                claims.subject(),
                claims.roles()
        );
        List<SimpleGrantedAuthority> authorities = claims.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static String authorizationHeader(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(SecurityHeaders.AUTHORIZATION));
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw new InvalidAccessTokenException();
        }
        return values.getFirst();
    }

    private static String bearerToken(String authorization) {
        if (!authorization.startsWith(SecurityHeaders.BEARER_PREFIX)) {
            throw new InvalidAccessTokenException();
        }
        String token = authorization.substring(SecurityHeaders.BEARER_PREFIX.length());
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidAccessTokenException();
        }
        return token;
    }
}
