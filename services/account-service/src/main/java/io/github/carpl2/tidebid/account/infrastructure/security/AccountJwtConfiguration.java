package io.github.carpl2.tidebid.account.infrastructure.security;

import io.github.carpl2.tidebid.security.JwtAccessTokenIssuer;
import io.github.carpl2.tidebid.security.JwtTokenSettings;
import io.github.carpl2.tidebid.security.RsaPemKeyLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile({"local-db", "nacos"})
public class AccountJwtConfiguration {

    @Bean
    @ConditionalOnMissingBean(JwtAccessTokenIssuer.class)
    JwtAccessTokenIssuer accountJwtAccessTokenIssuer(
            @Value("${TIDEBID_JWT_PRIVATE_KEY_PATH}") String privateKeyPath,
            @Value("${TIDEBID_JWT_PUBLIC_KEY_PATH}") String publicKeyPath,
            Clock clock
    ) throws IOException, GeneralSecurityException {
        return new JwtAccessTokenIssuer(
                RsaPemKeyLoader.loadKeyPair(Path.of(privateKeyPath), Path.of(publicKeyPath)),
                JwtTokenSettings.tideBidDefaults(),
                clock
        );
    }
}
