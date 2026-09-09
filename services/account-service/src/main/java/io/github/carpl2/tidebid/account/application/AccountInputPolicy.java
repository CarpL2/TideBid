package io.github.carpl2.tidebid.account.application;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

final class AccountInputPolicy {

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,48}");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{4,32}");

    private AccountInputPolicy() {
    }

    static boolean isValidRequestId(String requestId) {
        return requestId != null && REQUEST_ID_PATTERN.matcher(requestId).matches();
    }

    static Optional<String> canonicalUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            return Optional.empty();
        }
        return Optional.of(username.toLowerCase(Locale.ROOT));
    }
}
