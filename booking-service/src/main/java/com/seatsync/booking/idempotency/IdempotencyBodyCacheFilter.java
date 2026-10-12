package com.seatsync.booking.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Caches the raw request body for the two idempotent POST endpoints
 * ({@code POST /api/holds}, {@code POST /api/bookings}) whenever an
 * {@code Idempotency-Key} header is present, BEFORE Jackson binding consumes the
 * stream. The conventions (section 6.3) define {@code request_hash} as the
 * sha256 of the RAW body, so the exact bytes the client sent must be hashed,
 * not a re-serialized DTO.
 *
 * <p>Because the body is buffered in memory it is capped at
 * {@value #MAX_BODY_BYTES} bytes (the real bodies are under 200 bytes); larger
 * bodies are rejected with 413 before anything is read into memory.
 *
 * <p>Requests without the header (including every other endpoint) pass through
 * untouched.
 */
@Component
public class IdempotencyBodyCacheFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Generous for the two JSON bodies this applies to, small enough to buffer safely. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    static final Set<String> IDEMPOTENT_POST_PATHS = Set.of("/api/holds", "/api/bookings");

    static final String RAW_BODY_ATTRIBUTE = IdempotencyBodyCacheFilter.class.getName() + ".rawBody";

    private final ObjectMapper objectMapper;

    public IdempotencyBodyCacheFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && IDEMPOTENT_POST_PATHS.contains(request.getRequestURI())
                && request.getHeader(IDEMPOTENCY_KEY_HEADER) != null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            rejectTooLarge(request, response);
            return;
        }
        byte[] rawBody = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (rawBody.length > MAX_BODY_BYTES) {
            rejectTooLarge(request, response);
            return;
        }
        request.setAttribute(RAW_BODY_ATTRIBUTE, rawBody);
        chain.doFilter(new CachedBodyRequest(request, rawBody), response);
    }

    /** The raw bytes cached by this filter, or {@code null} when it did not run. */
    public static byte[] cachedRawBody(HttpServletRequest request) {
        return request.getAttribute(RAW_BODY_ATTRIBUTE) instanceof byte[] bytes ? bytes : null;
    }

    private void rejectTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpStatus status = HttpStatus.valueOf(413);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "Request body exceeds " + MAX_BODY_BYTES + " bytes");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
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
