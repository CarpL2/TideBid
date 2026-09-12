package io.github.carpl2.tidebid.auction.infrastructure.storage;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "tidebid.auction.storage",
        name = "enabled",
        havingValue = "true"
)
public class AlibabaOssClientConfiguration {

    @Bean(destroyMethod = "close")
    OSSClient auctionOssClient(AuctionStorageProperties properties) {
        return OSSClient.newBuilder()
                .endpoint(properties.endpoint())
                .region(properties.region())
                .credentialsProvider(new StaticCredentialsProvider(
                        properties.accessKeyId(),
                        properties.accessKeySecret()
                ))
                .build();
    }
}
