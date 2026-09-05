package io.github.carpl2.tidebid.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdsTest {

    @Test
    void acceptsSafeClientTraceId() {
        assertThat(TraceIds.resolve("client_trace-1234")).isEqualTo("client_trace-1234");
    }

    @Test
    void replacesUnsafeClientTraceId() {
        String resolved = TraceIds.resolve("bad trace id\nforged");

        assertThat(resolved).hasSize(32).matches("[a-f0-9]{32}");
    }
}
