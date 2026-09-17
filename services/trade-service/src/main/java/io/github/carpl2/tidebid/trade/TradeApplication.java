package io.github.carpl2.tidebid.trade;

import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeOutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({TradeRocketMqProperties.class, TradeOutboxProperties.class})
@EnableScheduling
public class TradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}
