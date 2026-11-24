package com.rackwatch.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Security headers, set on every response.
 *
 * The Content-Security-Policy is the strict one the dashboard is built
 * for: everything must come from this origin ('self') — scripts, styles,
 * images, fonts and fetch calls — with no inline scripts or styles and
 * no plugins. The dashboard loads Chart.js from /js (vendored, Part 5)
 * and talks to /api on the same origin, so 'self' covers everything it
 * needs and nothing else. It is NOT set on /api/**, /swagger-ui/**,
 * /v3/api-docs/** or /actuator/**: those are JSON or the Swagger UI,
 * which needs inline scripts of its own — a 'self'-only CSP would break
 * the docs page. The X-Content-Type-Options header is set everywhere:
 * it stops the browser sniffing a JSON or text response as HTML.
 * Runs first (highest precedence) so the headers survive even when a
 * later filter rejects the request (401/413) — those responses are
 * still served to a browser. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self'; "
            + "img-src 'self'; "
            + "font-src 'self'; "
            + "connect-src 'self'; "
            + "object-src 'none'; "
            + "base-uri 'self'; "
            + "frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")
                && !path.startsWith("/swagger-ui")
                && !path.startsWith("/v3/api-docs")
                && !path.startsWith("/actuator")) {
            response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        }
        chain.doFilter(request, response);
    }
}
