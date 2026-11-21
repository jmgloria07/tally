package app.tally.auth;

import app.tally.common.ApiError;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign in and out (spec §6.4). There is no "who am I" endpoint: the SPA learns it is signed out when any API
 * call returns 401.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password())
                .<ResponseEntity<?>>map(token -> ResponseEntity.noContent()
                        .header(HttpHeaders.SET_COOKIE, cookie(token, AuthTokenService.LIFETIME).toString())
                        .build())
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ApiError("Invalid username or password")));
    }

    /** Clears the cookie on this device only. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(AuthCookieFilter.COOKIE_NAME, value)
                .path("/")
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .build();
    }
}
