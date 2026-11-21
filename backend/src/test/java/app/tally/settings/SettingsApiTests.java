package app.tally.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.tally.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SettingsApiTests extends IntegrationTest {

    private Cookie cookie;

    @BeforeEach
    void signInFirst() throws Exception {
        cookie = signIn();
    }

    @Test
    void emptyDatabaseReturnsInCodeDefaultsWithoutWritingRows() throws Exception {
        mvc.perform(get("/api/settings").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"caloriesTarget": 2000, "proteinTarget": 150, "carbsTarget": 220, "fatTarget": 60,
                         "goal": "Build strength and improve body composition", "dietaryPreferences": "",
                         "injuriesLimitations": "",
                         "coachingStyle": "Practical, direct, encouraging; prioritize consistency over perfection."}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        assertThat(count("settings")).isZero();
    }

    @Test
    void putStoresCanonicalPlainText() throws Exception {
        mvc.perform(put("/api/settings").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("""
                        {"caloriesTarget": 2100.0, "proteinTarget": 112.5, "carbsTarget": 200, "fatTarget": 70,
                         "goal": "  Get stronger  ", "dietaryPreferences": "No peanuts\\nLow lactose",
                         "injuriesLimitations": "", "coachingStyle": "Direct"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caloriesTarget").value(2100))
                .andExpect(jsonPath("$.goal").value("Get stronger"));

        assertThat(count("settings")).isEqualTo(8);
        assertThat(value("calories_target")).isEqualTo("2100");
        assertThat(value("protein_target")).isEqualTo("112.5");
        assertThat(value("dietary_preferences")).isEqualTo("No peanuts\nLow lactose");
    }

    @Test
    void putValidatesRanges() throws Exception {
        mvc.perform(put("/api/settings").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("""
                        {"caloriesTarget": 100, "proteinTarget": 150, "carbsTarget": 220, "fatTarget": 60}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Calories target must be between 500 and 10000"));
    }

    private String value(String key) {
        return jdbc.queryForObject("SELECT value FROM settings WHERE key = ?", String.class, key);
    }
}
