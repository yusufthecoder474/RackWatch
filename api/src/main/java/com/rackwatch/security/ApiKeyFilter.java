package com.rackwatch.security;

import com.rackwatch.config.RackwatchProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** API-key auth for the whole application, as a plain servlet filter
 * (no Spring Security — this project has one key, not users and roles).
 * Every request needs a valid X-API-Key header, except:
 *  - the paths listed in rackwatch.security.public-paths, matched
 *    EXACTLY (String equals, never startsWith): today that is only
 *    /actuator/health, so /actuator/health/liveness still needs a key;
 *  - the static dashboard files (/, /index.html, /css/dashboard.css,
 *    /js/dashboard.js, /js/chart.umd.min.js, /favicon.ico), which carry
 *    no data — every data call the dashboard makes goes through /api
 *    and needs the key. The set is exact, not a prefix: a new static
 *    file must be added here by hand, or it will be rejected with 401.
 * Comparison is constant-time (MessageDigest.isEqual) so the time taken
 * does not reveal how many leading characters of a guessed key were
 * right. Runs after the request-size filter (order 2). */
@Component
@Order(2)
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Set<String> STATIC_DASHBOARD_FILES =
            Set.of("/", "/index.html", "/css/dashboard.css",
                    "/js/dashboard.js", "/js/chart.umd.min.js", "/favicon.ico");

    private final RackwatchProperties properties;

    public ApiKeyFilter(RackwatchProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (properties.security().publicPaths().contains(path)
                || STATIC_DASHBOARD_FILES.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader("X-API-Key");
        if (provided == null || !constantTimeEquals(provided, properties.apiKey())) {
            FilterErrorWriter.write(response, HttpStatus.UNAUTHORIZED,
                    "Missing or invalid API key", path);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
