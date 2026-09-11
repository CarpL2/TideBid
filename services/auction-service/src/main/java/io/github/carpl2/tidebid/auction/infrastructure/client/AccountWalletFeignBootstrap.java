package io.github.carpl2.tidebid.auction.infrastructure.client;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local-db", "nacos"})
@EnableFeignClients(clients = AccountWalletFeignClient.class)
class AccountWalletFeignBootstrap {
}
