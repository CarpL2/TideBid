package io.github.carpl2.tidebid.account.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local-db", "nacos"})
@EnableConfigurationProperties(InternalServiceTokenProperties.class)
class InternalServiceSecurityConfiguration {
}
