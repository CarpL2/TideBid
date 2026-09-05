package io.github.carpl2.tidebid.account;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.web.GlobalExceptionHandler;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("standalone")
@Import(AccountApplicationTest.ProbeConfiguration.class)
class AccountApplicationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate client;

    @Test
    void servesHealthAndIdentity() {
        ResponseEntity<JsonNode> health = client.getForEntity("/actuator/health", JsonNode.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).isNotNull();
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(health.getBody().has("components")).isFalse();

        ResponseEntity<JsonNode> info = client.getForEntity("/actuator/info", JsonNode.class);
        assertThat(info.getStatusCode().value()).isEqualTo(200);
        assertThat(info.getBody()).isNotNull();
        assertThat(info.getBody().path("app").path("name").asText()).isEqualTo("tidebid-account");
        assertThat(context.getBeansOfType(TraceIdFilter.class)).hasSize(1);
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class)).hasSize(1);
        assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.cloud.nacos.config.enabled", Boolean.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/does-not-exist"})
    void rejectsUnexposedEndpointsWithUniformJson(String path) {
        ResponseEntity<JsonNode> response = client.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_NOT_FOUND");
        assertThat(response.getBody().path("traceId").asText())
                .isEqualTo(response.getHeaders().getFirst("X-Trace-Id"))
                .matches("[a-f0-9]{32}");
    }

    @Test
    void preservesTraceIdInSuccessfulResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "account-trace-1234");
        ResponseEntity<JsonNode> response = client.exchange("/_test/success", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("code").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("data").asText()).isEqualTo("probe");
        assertThat(response.getBody().path("traceId").asText()).isEqualTo("account-trace-1234");
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("account-trace-1234");
    }

    @Test
    void mapsBusinessFailureAndReplacesInvalidTraceId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "invalid trace");
        ResponseEntity<JsonNode> response = client.exchange("/_test/conflict", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_CONFLICT");
        assertThat(response.getBody().path("message").asText()).isEqualTo("Conflict for test");
        assertThat(response.getBody().path("data").isNull()).isTrue();
        assertThat(response.getBody().path("traceId").asText())
                .isEqualTo(response.getHeaders().getFirst("X-Trace-Id")).matches("[a-f0-9]{32}");
    }

    @Test
    void mapsInvalidPayloadsToBadRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (String body : new String[]{"{\"value\":\"\"}", "{broken-json"}) {
            ResponseEntity<JsonNode> response = client.postForEntity("/_test/validate",
                    new HttpEntity<>(body, headers), JsonNode.class);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_INVALID_ARGUMENT");
        }
    }

    @Test
    void hidesUnexpectedFailureDetails() {
        ResponseEntity<JsonNode> response = client.getForEntity("/_test/failure", JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_INTERNAL_ERROR");
        assertThat(response.getBody().toString()).doesNotContain("internal-test-detail");
        assertThat(response.getBody().path("traceId").asText())
                .isEqualTo(response.getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    void preservesMethodNotAllowedStatus() {
        ResponseEntity<JsonNode> response = client.postForEntity("/_test/success", null, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody().path("code").asText()).isEqualTo("COMMON_METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    // Only compiled into test-classes; these endpoints must not appear in the executable JAR.
    @RestController
    static class ProbeController {
        @GetMapping("/_test/success")
        ApiResponse<String> success(HttpServletRequest request) {
            return ApiResponse.success("probe", (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
        }

        @GetMapping("/_test/conflict")
        void conflict() {
            throw new BusinessException(CommonErrorCode.CONFLICT, "Conflict for test");
        }

        @GetMapping("/_test/failure")
        void failure() {
            throw new IllegalStateException("internal-test-detail");
        }

        @PostMapping("/_test/validate")
        void validate(@Valid @RequestBody ProbePayload payload) {
        }
    }

    record ProbePayload(@NotBlank String value) {
    }
}
