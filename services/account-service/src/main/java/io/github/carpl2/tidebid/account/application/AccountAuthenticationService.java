package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore;
import io.github.carpl2.tidebid.account.application.port.AccountAuthenticationStore.StoredAccount;
import io.github.carpl2.tidebid.account.domain.AccountErrorCode;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.security.IssuedAccessToken;
import io.github.carpl2.tidebid.security.JwtAccessTokenIssuer;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Profile({"local-db", "nacos"})
public class AccountAuthenticationService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String DUMMY_PASSWORD = "TideBid dummy credential check";

    private final PasswordEncoder passwordEncoder;
    private final AccountAuthenticationStore authenticationStore;
    private final JwtAccessTokenIssuer tokenIssuer;
    private final String dummyPasswordHash;

    public AccountAuthenticationService(
            PasswordEncoder passwordEncoder,
            AccountAuthenticationStore authenticationStore,
            JwtAccessTokenIssuer tokenIssuer
    ) {
        this.passwordEncoder = passwordEncoder;
        this.authenticationStore = authenticationStore;
        this.tokenIssuer = tokenIssuer;
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    public IssuedAccessToken authenticate(AuthenticateAccountCommand command) {
        if (command == null) {
            throw invalid("Login request is required");
        }
        if (!AccountInputPolicy.isValidRequestId(command.requestId())) {
            throw invalid("X-Request-Id must contain 8 to 48 letters, digits, underscores, or hyphens");
        }

        Optional<StoredAccount> account = AccountInputPolicy.canonicalUsername(command.username())
                .flatMap(authenticationStore::findByCanonicalUsername);
        String candidatePassword = command.password() == null ? "" : command.password();
        String expectedHash = account.map(StoredAccount::passwordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(candidatePassword, expectedHash);

        if (account.isEmpty() || !passwordMatches) {
            throw new BusinessException(AccountErrorCode.INVALID_CREDENTIALS);
        }

        StoredAccount authenticatedAccount = account.orElseThrow();
        if (!ACTIVE_STATUS.equals(authenticatedAccount.status())) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_DISABLED);
        }
        return tokenIssuer.issue(
                authenticatedAccount.canonicalUsername(),
                authenticatedAccount.userId(),
                authenticatedAccount.roles()
        );
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_ARGUMENT, message);
    }
}
