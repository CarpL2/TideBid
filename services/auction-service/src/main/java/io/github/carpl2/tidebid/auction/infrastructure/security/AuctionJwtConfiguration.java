package io.github.carpl2.tidebid.auction.infrastructure.security;

import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtTokenSettings;
import io.github.carpl2.tidebid.security.RsaPemKeyLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile({"local-db", "nacos"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuctionJwtConfiguration {

    @Bean
    @ConditionalOnMissingBean(RSAPublicKey.class)
    RSAPublicKey auctionJwtPublicKey(
            @Value("${TIDEBID_JWT_PUBLIC_KEY_PATH}") String publicKeyPath
    ) throws IOException, GeneralSecurityException {
        return RsaPemKeyLoader.loadPublicKey(Path.of(publicKeyPath));
    }

    @Bean
    @ConditionalOnMissingBean(JwtAccessTokenVerifier.class)
    JwtAccessTokenVerifier auctionJwtAccessTokenVerifier(RSAPublicKey publicKey, Clock clock) {
        return new JwtAccessTokenVerifier(publicKey, JwtTokenSettings.tideBidDefaults(), clock);
    }
}
