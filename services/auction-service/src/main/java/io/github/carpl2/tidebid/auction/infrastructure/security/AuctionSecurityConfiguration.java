package io.github.carpl2.tidebid.auction.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.core.ErrorCode;
import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionAccountClientProperties;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuctionSecurityConfiguration {

    @Bean
    @Profile({"local-db", "nacos"})
    SecurityFilterChain auctionSecurityFilterChain(
            HttpSecurity http,
            JwtAccessTokenVerifier tokenVerifier,
            ObjectMapper objectMapper,
            ObjectProvider<AuctionAccountClientProperties> internalTokenProperties
    ) throws Exception {
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                writeFailure(request, response, objectMapper, CommonErrorCode.UNAUTHENTICATED);
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                writeFailure(request, response, objectMapper, CommonErrorCode.FORBIDDEN);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/admin/assets/**").hasRole("ADMIN")
                        .requestMatchers(
                                "/api/assets/**",
                                "/api/auctions/**",
                                "/api/bids/**",
                                "/api/registrations/**"
                        ).authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(
                        new AuctionBearerTokenFilter(tokenVerifier, authenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(
                        new AuctionInternalServiceTokenFilter(
                                internalTokenProperties.getIfAvailable(() -> new AuctionAccountClientProperties("")),
                                objectMapper),
                        AuctionBearerTokenFilter.class
                );
        return http.build();
    }

    @Bean
    @Profile("standalone")
    SecurityFilterChain standaloneAuctionSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    private static void writeFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper objectMapper,
            ErrorCode errorCode
    ) throws IOException {
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
