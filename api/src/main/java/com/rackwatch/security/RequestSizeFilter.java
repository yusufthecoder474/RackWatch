package com.rackwatch.security;

import com.rackwatch.config.RackwatchProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces the 64 KiB request-body limit by COUNTING THE BYTES
 * ACTUALLY READ, not by trusting the Content-Length header. A chunked
 * request sends no Content-Length at all, and a lying client can send
 * a small Content-Length with a large body — both are caught here,
 * because the counting stream throws the moment the limit is passed,
 * while Jackson is still reading. A correct Content-Length above the
 * limit is rejected immediately without reading the body at all.
 * Runs first (order 1), before the API-key filter, so an oversized
 * body is rejected before any further work is done on it. */
@Component
@Order(1)
public class RequestSizeFilter extends OncePerRequestFilter {

    private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH");

    private final long maxRequestBytes;

    public RequestSizeFilter(RackwatchProperties properties) {
        this.maxRequestBytes = properties.ingestion().maxRequestBytes();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!METHODS_WITH_BODY.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String contentLength = request.getHeader("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > maxRequestBytes) {
                    FilterErrorWriter.write(response, HttpStatus.PAYLOAD_TOO_LARGE,
                            "Request body exceeds the 64 KiB limit", request.getRequestURI());
                    return;
                }
            } catch (NumberFormatException ignored) {
                // A malformed header is not trusted either way; the
                // counting stream below is the real enforcement.
            }
        }
        chain.doFilter(new LimitedRequestWrapper(request, maxRequestBytes), response);
    }

    /** Counts every byte read from the request body and throws the
     * moment the count passes the limit. */
    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limitBytes;
        private long bytesRead;

        LimitedInputStream(ServletInputStream delegate, long limitBytes) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = delegate.read(buffer, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            bytesRead += n;
            if (bytesRead > limitBytes) {
                throw new SizeLimitExceededException(limitBytes, bytesRead);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class LimitedRequestWrapper extends HttpServletRequestWrapper {

        private final long limitBytes;
        private LimitedInputStream inputStream;
        private BufferedReader reader;

        LimitedRequestWrapper(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new LimitedInputStream(super.getInputStream(), limitBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                reader = new BufferedReader(new InputStreamReader(
                        getInputStream(),
                        encoding != null ? encoding : StandardCharsets.UTF_8.name()));
            }
            return reader;
        }
    }
}
