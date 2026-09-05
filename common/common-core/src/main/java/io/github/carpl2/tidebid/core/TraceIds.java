package io.github.carpl2.tidebid.core;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creates and validates trace identifiers accepted at the HTTP boundary.
 */
public final class TraceIds {

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private TraceIds() {
    }

    public static String create() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String resolve(String candidate) {
        if (candidate != null && SAFE_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return create();
    }

    public static boolean isValid(String candidate) {
        return candidate != null && SAFE_TRACE_ID.matcher(candidate).matches();
    }
}
