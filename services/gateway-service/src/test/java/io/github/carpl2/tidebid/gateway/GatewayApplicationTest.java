package io.github.carpl2.tidebid.gateway;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("standalone")
@Import(GatewayApplicationTest.ProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class GatewayApplicationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebTestClient client;

    @Test
    void servesHealthAndIdentity() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
        client.get().uri("/actuator/info").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.app.name").isEqualTo("tidebid-gateway");
        assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.cloud.nacos.config.enabled", Boolean.class)).isFalse();
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext.class);
        assertThat(ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet",
                getClass().getClassLoader())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/does-not-exist"})
    void rejectsUnexposedEndpointsWithJsonEvenForBrowserRequests(String path) {
        client.get().uri(path).accept(MediaType.TEXT_HTML)
                .header("X-Trace-Id", "gateway-trace-1234").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("X-Trace-Id", "gateway-trace-1234")
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_NOT_FOUND")
                .jsonPath("$.traceId").isEqualTo("gateway-trace-1234")
                .jsonPath("$.data").isEmpty();
    }

    @Test
    void generatesOrReplacesTraceId() {
        var response = client.get().uri("/does-not-exist")
                .header("X-Trace-Id", "invalid trace").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueMatches("X-Trace-Id", "[a-f0-9]{32}");
        String traceId = response.returnResult(String.class).getResponseHeaders().getFirst("X-Trace-Id");
        response.expectBody().jsonPath("$.traceId").isEqualTo(traceId);
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk().expectHeader().valueMatches("X-Trace-Id", "[a-f0-9]{32}");
    }

    @Test
    void mapsBusinessFailuresOutsideControllers() {
        client.get().uri("/_test/conflict").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_CONFLICT")
                .jsonPath("$.message").isEqualTo("Conflict for test");
    }

    @Test
    void preservesFrameworkStatusWithoutExposingInternalReason() {
        client.get().uri("/_test/bad-request").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_INVALID_ARGUMENT")
                .jsonPath("$.message").isEqualTo("Invalid request");
        client.get().uri("/_test/unavailable").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_SERVICE_UNAVAILABLE");
    }

    @Test
    void hidesUnexpectedFailureDetails(CapturedOutput output) {
        client.get().uri("/_test/failure")
                .header("X-Trace-Id", "gateway-trace-5678").exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_INTERNAL_ERROR")
                .jsonPath("$.message").isEqualTo("An internal error occurred")
                .jsonPath("$.traceId").isEqualTo("gateway-trace-5678");
        assertThat(output.getAll()).doesNotContain("internal-test-detail");
        assertThat(output.getAll()).contains("traceId=gateway-trace-5678");
    }

    @Test
    void preservesAllowHeaderForMethodNotAllowed() {
        client.post().uri("/_test/method").exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals("Allow", "GET")
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_METHOD_NOT_ALLOWED");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        // Test-only filter verifies errors before any route/controller is selected.
        @Bean
        @Order(-100)
        WebFilter failureProbe() {
            return (exchange, chain) -> switch (exchange.getRequest().getPath().value()) {
                case "/_test/conflict" -> Mono.error(
                        new BusinessException(CommonErrorCode.CONFLICT, "Conflict for test"));
                case "/_test/bad-request" -> Mono.error(
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "internal-test-detail"));
                case "/_test/unavailable" -> Mono.error(
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "internal-test-detail"));
                case "/_test/failure" -> Mono.error(new IllegalStateException("internal-test-detail"));
                case "/_test/method" -> Mono.error(
                        new MethodNotAllowedException(HttpMethod.POST, List.of(HttpMethod.GET)));
                default -> chain.filter(exchange);
            };
        }
    }
}
