package io.github.carpl2.tidebid.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CommonWebAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class));

    @Test
    void registersDefaultWebInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TraceIdFilter.class);
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
        });
    }

    @Test
    void doesNotRegisterServletInfrastructureInNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TraceIdFilter.class);
                    assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
                });
    }
}
