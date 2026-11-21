package app.tally.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs method, path, status and duration for API calls, and nothing else: no bodies, prompts, replies,
 * passwords, tokens or query strings (spec §2). Also enforces the 1 MB request body limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 1_000_000;

    private static final Logger log = LoggerFactory.getLogger("app.tally.http");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                response.setStatus(413);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"Request body is too large (limit 1 MB)\"}");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            if (request.getRequestURI().startsWith("/api/")) {
                long millis = (System.nanoTime() - start) / 1_000_000;
                log.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), millis);
            }
        }
    }
}
