package io.github.carpl2.tidebid.trade.infrastructure.security;

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

final class TradeBearerTokenFilter extends OncePerRequestFilter {

    private final JwtAccessTokenVerifier verifier;
    private final AuthenticationEntryPoint entryPoint;

    TradeBearerTokenFilter(JwtAccessTokenVerifier verifier, AuthenticationEntryPoint entryPoint) {
        this.verifier = verifier;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = authorizationHeader(request);
            if (header != null) authenticate(request, bearerToken(header));
        } catch (InvalidAccessTokenException exception) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Access token is invalid"));
            return;
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        JwtClaims claims = verifier.verify(token);
        AuthenticatedUser principal = new AuthenticatedUser(claims.userId(), claims.subject(), claims.roles());
        List<SimpleGrantedAuthority> authorities = claims.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static String authorizationHeader(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(SecurityHeaders.AUTHORIZATION));
        if (values.isEmpty()) return null;
        if (values.size() != 1) throw new InvalidAccessTokenException();
        return values.getFirst();
    }

    private static String bearerToken(String header) {
        if (!header.startsWith(SecurityHeaders.BEARER_PREFIX)) throw new InvalidAccessTokenException();
        String token = header.substring(SecurityHeaders.BEARER_PREFIX.length());
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidAccessTokenException();
        }
        return token;
    }
}
