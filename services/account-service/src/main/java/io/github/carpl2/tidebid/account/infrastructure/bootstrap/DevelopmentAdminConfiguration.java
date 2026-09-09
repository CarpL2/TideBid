package io.github.carpl2.tidebid.account.infrastructure.bootstrap;

import io.github.carpl2.tidebid.account.application.DevelopmentAdminBootstrapService;
import io.github.carpl2.tidebid.account.application.DevelopmentAdminCommand;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local-db", "nacos"})
@EnableConfigurationProperties(DevelopmentAdminProperties.class)
public class DevelopmentAdminConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "tidebid.development-admin",
            name = "enabled",
            havingValue = "true"
    )
    ApplicationRunner developmentAdminRunner(
            DevelopmentAdminBootstrapService bootstrapService,
            DevelopmentAdminProperties properties
    ) {
        return arguments -> bootstrapService.ensureAdmin(new DevelopmentAdminCommand(
                properties.username(),
                properties.password(),
                properties.nickname()
        ));
    }
}
