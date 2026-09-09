package io.github.carpl2.tidebid.account.application;

import java.nio.charset.StandardCharsets;
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

    static Optional<String> normalizedNickname(String nickname) {
        if (nickname == null) {
            return Optional.empty();
        }
        String normalized = nickname.strip();
        if (normalized.isEmpty() || normalized.length() > 64) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    static boolean hasValidPasswordCharacterLength(String password) {
        return password != null && password.length() >= 8 && password.length() <= 64;
    }

    static boolean fitsBcryptByteLimit(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
