package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AuctionPersistenceConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    MetaObjectHandler auctionUtcAuditMetaObjectHandler(Clock utcClock) {
        return new AuctionUtcAuditMetaObjectHandler(utcClock);
    }

    @Bean
    MybatisPlusInterceptor auctionMybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
