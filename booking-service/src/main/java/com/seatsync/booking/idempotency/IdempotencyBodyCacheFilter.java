package com.seatsync.booking.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Caches the raw request body for the two idempotent POST endpoints
 * ({@code POST /api/holds}, {@code POST /api/bookings}) whenever an
 * {@code Idempotency-Key} header is present, BEFORE Jackson binding consumes the
 * stream. The conventions (section 6.3) define {@code request_hash} as the
 * sha256 of the RAW body, so the exact bytes the client sent must be hashed —
 * not a re-serialized DTO.
 *
 * <p>Requests without the header (including every other endpoint) pass through
 * untouched.
 */
@Component
public class IdempotencyBodyCacheFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    static final Set<String> IDEMPOTENT_POST_PATHS = Set.of("/api/holds", "/api/bookings");

    static final String RAW_BODY_ATTRIBUTE = IdempotencyBodyCacheFilter.class.getName() + ".rawBody";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && IDEMPOTENT_POST_PATHS.contains(request.getRequestURI())
                && request.getHeader(IDEMPOTENCY_KEY_HEADER) != null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] rawBody = request.getInputStream().readAllBytes();
        request.setAttribute(RAW_BODY_ATTRIBUTE, rawBody);
        chain.doFilter(new CachedBodyRequest(request, rawBody), response);
    }

    /** The raw bytes cached by this filter, or {@code null} when it did not run. */
    public static byte[] cachedRawBody(HttpServletRequest request) {
        return request.getAttribute(RAW_BODY_ATTRIBUTE) instanceof byte[] bytes ? bytes : null;
    }

    /** Serves the already-consumed body to downstream readers (Jackson binding). */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream delegate = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return delegate.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Non-blocking IO not supported on cached body");
                }

                @Override
                public int read() {
                    return delegate.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return delegate.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() != null
                    ? Charset.forName(getCharacterEncoding())
                    : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }
    }
}
