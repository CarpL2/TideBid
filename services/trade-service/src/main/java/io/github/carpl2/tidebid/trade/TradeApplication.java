package io.github.carpl2.tidebid.trade;

import io.github.carpl2.tidebid.trade.infrastructure.config.TradeRocketMqProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeOutboxProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeOrderProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradeAccountClientProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradePaymentProperties;
import io.github.carpl2.tidebid.trade.infrastructure.config.TradePaymentTimeoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({TradeRocketMqProperties.class, TradeOutboxProperties.class,
        TradeOrderProperties.class, TradeAccountClientProperties.class, TradePaymentProperties.class,
        TradePaymentTimeoutProperties.class})
@EnableScheduling
public class TradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}
