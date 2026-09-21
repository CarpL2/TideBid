package io.github.carpl2.tidebid.auction.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionAccountClientProperties;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionInternalServiceTokenFilterTest {

    private static final String TOKEN = "internal-token-123456789012345678901234";

    @Test
    void rejectsMissingAndWrongInternalTokens() throws Exception {
        AuctionInternalServiceTokenFilter filter = filter();

        MockHttpServletResponse missing = invoke(filter, null);
        MockHttpServletResponse wrong = invoke(filter, "wrong-token");

        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(wrong.getStatus()).isEqualTo(403);
    }

    @Test
    void acceptsExactlyOneMatchingToken() throws Exception {
        AuctionInternalServiceTokenFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/realtime/auctions/1/snapshot");
        request.addHeader(SecurityHeaders.INTERNAL_SERVICE_TOKEN, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        FilterChain chain = (req, res) -> called.set(true);

        filter.doFilter(request, response, chain);

        assertThat(called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotFilterPublicPaths() throws Exception {
        AuctionInternalServiceTokenFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auctions/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called).isTrue();
    }

    private static AuctionInternalServiceTokenFilter filter() {
        return new AuctionInternalServiceTokenFilter(
                new AuctionAccountClientProperties(TOKEN), new ObjectMapper());
    }

    private static MockHttpServletResponse invoke(AuctionInternalServiceTokenFilter filter, String token)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/realtime/auctions/1/snapshot");
        if (token != null) request.addHeader(SecurityHeaders.INTERNAL_SERVICE_TOKEN, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        return response;
    }
}
