package io.github.carpl2.tidebid.account;

import io.github.carpl2.tidebid.security.JwtAccessTokenIssuer;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtTokenSettings;
import io.github.carpl2.tidebid.security.RsaKeyPairMaterial;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

@TestConfiguration(proxyBeanMethods = false)
public class AccountJwtTestConfiguration {

    @Bean
    RsaKeyPairMaterial testRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return new RsaKeyPairMaterial(
                (RSAPublicKey) keyPair.getPublic(),
                (RSAPrivateKey) keyPair.getPrivate()
        );
    }

    @Bean
    JwtAccessTokenIssuer testJwtAccessTokenIssuer(RsaKeyPairMaterial keyPair, Clock clock) {
        return new JwtAccessTokenIssuer(keyPair, JwtTokenSettings.tideBidDefaults(), clock);
    }

    @Bean
    JwtAccessTokenVerifier testJwtAccessTokenVerifier(RsaKeyPairMaterial keyPair, Clock clock) {
        return new JwtAccessTokenVerifier(keyPair.publicKey(), JwtTokenSettings.tideBidDefaults(), clock);
    }
}
