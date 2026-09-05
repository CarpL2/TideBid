package io.github.carpl2.tidebid.trade;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.carpl2.tidebid.web.GlobalExceptionHandler;
import io.github.carpl2.tidebid.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("standalone")
class TradeApplicationTest {

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
        assertThat(info.getBody().path("app").path("name").asText()).isEqualTo("tidebid-trade");
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
}
