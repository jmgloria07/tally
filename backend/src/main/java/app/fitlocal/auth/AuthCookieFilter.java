package app.fitlocal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /api/**} requests from the {@code fitlocal_auth} cookie (spec §6.3). Any failure leaves
 * the request unauthenticated; the entry point then answers 401 without saying why.
 *
 * <p>Not a Spring bean on purpose: Boot would also register a bean filter with the servlet container, and it
 * would run twice.
 */
public class AuthCookieFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "fitlocal_auth";

    private final AuthTokenService tokenService;
    private final UserRepository userRepository;

    public AuthCookieFilter(AuthTokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> username = readCookie(request).flatMap(token -> tokenService.verify(token,
                name -> userRepository.findByUsername(name).map(User::getPasswordHash)));
        username.ifPresent(name -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, null, List.of()));
            SecurityContextHolder.setContext(context);
        });
        chain.doFilter(request, response);
    }

    private static Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
