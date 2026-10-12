package com.seatsync.booking.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raw body is cached byte-for-byte for the downstream reader, only for the
 * two idempotent POST endpoints with the header, and oversized bodies are
 * rejected with a 413 ProblemDetail before being buffered.
 */
class IdempotencyBodyCacheFilterTest {

    private final IdempotencyBodyCacheFilter filter = new IdempotencyBodyCacheFilter(new ObjectMapper());

    @Test
    void cachesTheRawBodyAndReservesItForDownstreamReaders() throws Exception {
        byte[] body = "{\"eventId\":\"e\",\"seatId\":\"A-1-1\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = post("/api/holds", body);
        request.addHeader(IdempotencyBodyCacheFilter.IDEMPOTENCY_KEY_HEADER, "k1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(IdempotencyBodyCacheFilter.cachedRawBody(request)).isEqualTo(body);
        byte[] replayed = ((jakarta.servlet.http.HttpServletRequest) chain.getRequest())
                .getInputStream().readAllBytes();
        assertThat(replayed).isEqualTo(body);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void requestsWithoutTheHeaderOrOnOtherPathsPassThroughUntouched() throws Exception {
        MockHttpServletRequest noHeader = post("/api/holds", "{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest otherPath = post("/api/events/x/waitlist", "{}".getBytes(StandardCharsets.UTF_8));
        otherPath.addHeader(IdempotencyBodyCacheFilter.IDEMPOTENCY_KEY_HEADER, "k1");

        assertThat(filter.shouldNotFilter(noHeader)).isTrue();
        assertThat(filter.shouldNotFilter(otherPath)).isTrue();
    }

    @Test
    void oversizedBodyIsRejectedWith413ProblemDetailBeforeBuffering() throws Exception {
        byte[] huge = new byte[IdempotencyBodyCacheFilter.MAX_BODY_BYTES + 1];
        MockHttpServletRequest request = post("/api/bookings", huge);
        request.addHeader(IdempotencyBodyCacheFilter.IDEMPOTENCY_KEY_HEADER, "k1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("chain must not run").isNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":413").contains("/api/bookings");
    }

    @Test
    void oversizedStreamedBodyWithoutDeclaredLengthIsRejectedAfterABoundedRead() throws Exception {
        // A chunked upload reports no Content-Length, so the only protection is the
        // bounded read: at most MAX_BODY_BYTES + 1 bytes are ever buffered.
        byte[] huge = new byte[IdempotencyBodyCacheFilter.MAX_BODY_BYTES * 4];
        MockHttpServletRequest raw = post("/api/holds", huge);
        raw.addHeader(IdempotencyBodyCacheFilter.IDEMPOTENCY_KEY_HEADER, "k1");
        jakarta.servlet.http.HttpServletRequest chunked = new jakarta.servlet.http.HttpServletRequestWrapper(raw) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chunked, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(IdempotencyBodyCacheFilter.cachedRawBody(raw)).as("nothing cached for a rejected body").isNull();
    }

    private static MockHttpServletRequest post(String uri, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
