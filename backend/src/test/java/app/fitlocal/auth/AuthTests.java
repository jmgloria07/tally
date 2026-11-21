package app.fitlocal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.fitlocal.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AuthTests extends IntegrationTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    @Test
    void loginSetsHardenedCookie() throws Exception {
        authService.createUser(USERNAME, PASSWORD);
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(USERNAME, PASSWORD)))
                .andExpect(status().isNoContent())
                .andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).startsWith("fitlocal_auth=")
                .contains("Path=/")
                .contains("Max-Age=2592000")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict");
    }

    @Test
    void tokenExpiresExactlyThirtyDaysAfterLogin() throws Exception {
        Cookie cookie = signIn();
        String payload = new String(B64D.decode(cookie.getValue().split("\\.")[0]), StandardCharsets.UTF_8);
        long expectedExpiry = clock.instant().plus(Duration.ofDays(30)).getEpochSecond();
        assertThat(payload).isEqualTo("v1|" + USERNAME + "|" + expectedExpiry);

        clock.advance(Duration.ofDays(30).minusSeconds(1));
        mvc.perform(get("/api/meals").cookie(cookie)).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(2));
        mvc.perform(get("/api/meals").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordOrUnknownUserIsRejectedWithoutCookie() throws Exception {
        authService.createUser(USERNAME, PASSWORD);
        for (String body : new String[] {credentials(USERNAME, "wrong-password"), credentials("nobody", PASSWORD),
                "{}"}) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(jsonPath("$.error").value("Invalid username or password"));
        }
    }

    @Test
    void missingCookieIsRejectedWithJson() throws Exception {
        mvc.perform(get("/api/meals"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Not signed in\"}"));
    }

    @Test
    void tamperedOrGarbageCookiesAreRejected() throws Exception {
        authService.createUser("other", PASSWORD);
        Cookie valid = signIn();
        String[] parts = valid.getValue().split("\\.");
        String payload = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
        String[] fields = payload.split("\\|");
        byte[] signature = B64D.decode(parts[1]);
        signature[0] ^= 1;

        String tamperedSignature = parts[0] + "." + B64.encodeToString(signature);
        String tamperedUsername = encode("v1|other|" + fields[2]) + "." + parts[1];
        String tamperedExpiry = encode("v1|" + USERNAME + "|" + (Long.parseLong(fields[2]) + 86400)) + "." + parts[1];

        for (String token : new String[] {tamperedSignature, tamperedUsername, tamperedExpiry, "garbage",
                "a.b.c", "", "%%%.***"}) {
            mvc.perform(get("/api/meals").cookie(new Cookie(AuthCookieFilter.COOKIE_NAME, token)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/meals").cookie(valid)).andExpect(status().isOk());
    }

    @Test
    void passwordResetRevokesExistingTokens() throws Exception {
        Cookie cookie = signIn();
        mvc.perform(get("/api/meals").cookie(cookie)).andExpect(status().isOk());

        authService.resetPassword(USERNAME, "a-brand-new-password");

        mvc.perform(get("/api/meals").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsCookie() throws Exception {
        Cookie cookie = signIn();
        MvcResult result = mvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .startsWith("fitlocal_auth=;")
                .contains("Max-Age=0");
    }

    @Test
    void staticFilesAndLoginDoNotNeedCookie() throws Exception {
        int status = mvc.perform(get("/")).andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("nobody", "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
    }

    @Test
    void mutatingRequestsRequireJson() throws Exception {
        Cookie cookie = signIn();
        mvc.perform(post("/api/meals").cookie(cookie).contentType(MediaType.TEXT_PLAIN).content("food=x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/meals").cookie(cookie).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("food=x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/meals"))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    private static String credentials(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private static String encode(String text) {
        return B64.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
