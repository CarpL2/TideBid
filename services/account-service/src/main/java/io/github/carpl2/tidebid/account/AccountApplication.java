package io.github.carpl2.tidebid.account;

import io.github.carpl2.tidebid.account.infrastructure.config.AccountRocketMqProperties;
import io.github.carpl2.tidebid.account.infrastructure.config.AccountOutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({AccountRocketMqProperties.class, AccountOutboxProperties.class})
@EnableScheduling
public class AccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }
}
