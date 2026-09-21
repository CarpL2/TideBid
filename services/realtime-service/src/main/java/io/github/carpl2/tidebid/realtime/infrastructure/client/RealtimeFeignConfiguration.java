package io.github.carpl2.tidebid.realtime.infrastructure.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("nacos")
@ConditionalOnProperty(prefix = "tidebid.realtime.auction-client", name = "enabled", havingValue = "true")
@EnableFeignClients(clients = AuctionSnapshotFeignClient.class)
public class RealtimeFeignConfiguration { }
