package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile({"local-db", "nacos"})
@ConditionalOnProperty(prefix = "tidebid.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuctionSchedulingConfiguration {
}
