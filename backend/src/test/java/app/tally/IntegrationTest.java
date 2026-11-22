package app.tally;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.tally.auth.AuthCookieFilter;
import app.tally.auth.AuthService;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base for tests that need the full app. Each test class gets its own temp-file SQLite DB under
 * {@code target/} (not {@code :memory:}: every pooled connection would see a different empty DB, and a temp
 * dir that JUnit deletes can fail on Windows while the file is open). Tables are emptied before each test and
 * the clock is reset to {@link MutableClock#DEFAULT_NOW}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.TestClockConfig.class)
@DirtiesContext
public abstract class IntegrationTest {

    protected static final String USERNAME = "tester";
    protected static final String PASSWORD = "correct-horse-battery";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(ZoneId.systemDefault());
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        Path dir = newDataDir();
        registry.add("tally.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected AuthService authService;

    @BeforeEach
    void resetState() {
        for (String table : new String[] {"workout_sets", "workout_exercises", "workouts", "meals", "weigh_ins",
                "settings", "users", "ingested_messages"}) {
            jdbc.update("DELETE FROM " + table);
        }
        clock.reset();
    }

    protected static Path newDataDir() {
        try {
            Path base = Files.createDirectories(Path.of("target", "test-data"));
            return Files.createTempDirectory(base, "tally-").toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates the test user (if needed), signs in and returns the auth cookie. */
    protected Cookie signIn() throws Exception {
        if (!authService.userExists(USERNAME)) {
            authService.createUser(USERNAME, PASSWORD);
        }
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        String header = result.getResponse().getHeader("Set-Cookie");
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        return new Cookie(AuthCookieFilter.COOKIE_NAME, value);
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
