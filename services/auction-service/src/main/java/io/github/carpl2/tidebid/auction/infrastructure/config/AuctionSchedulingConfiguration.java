package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile({"local-db", "nacos"})
public class AuctionSchedulingConfiguration {
}
