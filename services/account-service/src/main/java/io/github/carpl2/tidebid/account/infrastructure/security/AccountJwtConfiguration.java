package io.github.carpl2.tidebid.account.infrastructure.security;

import io.github.carpl2.tidebid.security.JwtAccessTokenIssuer;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtTokenSettings;
import io.github.carpl2.tidebid.security.RsaKeyPairMaterial;
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
    @ConditionalOnMissingBean(RsaKeyPairMaterial.class)
    RsaKeyPairMaterial accountRsaKeyPair(
            @Value("${TIDEBID_JWT_PRIVATE_KEY_PATH}") String privateKeyPath,
            @Value("${TIDEBID_JWT_PUBLIC_KEY_PATH}") String publicKeyPath
    ) throws IOException, GeneralSecurityException {
        return RsaPemKeyLoader.loadKeyPair(Path.of(privateKeyPath), Path.of(publicKeyPath));
    }

    @Bean
    @ConditionalOnMissingBean(JwtAccessTokenIssuer.class)
    JwtAccessTokenIssuer accountJwtAccessTokenIssuer(RsaKeyPairMaterial keyPair, Clock clock) {
        return new JwtAccessTokenIssuer(
                keyPair,
                JwtTokenSettings.tideBidDefaults(),
                clock
        );
    }

    @Bean
    @ConditionalOnMissingBean(JwtAccessTokenVerifier.class)
    JwtAccessTokenVerifier accountJwtAccessTokenVerifier(RsaKeyPairMaterial keyPair, Clock clock) {
        return new JwtAccessTokenVerifier(
                keyPair.publicKey(),
                JwtTokenSettings.tideBidDefaults(),
                clock
        );
    }
}
