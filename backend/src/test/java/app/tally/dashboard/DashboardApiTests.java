package app.tally.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.tally.IntegrationTest;
import org.junit.jupiter.api.Test;

class DashboardApiTests extends IntegrationTest {

    @Test
    void summarizesTodayAndTheLastSevenDays() throws Exception {
        meal("2026-11-20", "Oats", 420, 32.25, "2026-11-20T08:00:00");
        meal("2026-11-20", "Rice", 830, 48, "2026-11-20T13:00:00");
        meal("2026-11-18", "Pasta", 2150, 155, "2026-11-18T19:00:00");
        meal("2026-11-13", "Too old", 5000, 5, "2026-11-13T19:00:00");
        meal("2026-11-22", "Future", 5000, 5, "2026-11-22T19:00:00");
        workout("2026-11-18", "2026-11-18T18:00:00");
        workout("2026-11-18", "2026-11-18T19:00:00");
        workout("2026-11-16", "2026-11-16T18:00:00");
        weighIn("2026-10-21", 73.5, "2026-10-21T07:00:00"); // day 31: outside the chart window
        weighIn("2026-10-22", 73.2, "2026-10-22T07:00:00"); // day 30: first day inside
        weighIn("2026-11-15", 72.4, "2026-11-15T07:00:00");
        weighIn("2026-11-20", 72.1, "2026-11-20T07:00:00");

        mvc.perform(get("/api/dashboard").cookie(signIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value("2026-11-20"))
                .andExpect(jsonPath("$.targets.calories").value(2000.0))
                .andExpect(jsonPath("$.todayTotals.calories").value(1250.0))
                .andExpect(jsonPath("$.todayTotals.proteinG").value(80.3))
                .andExpect(jsonPath("$.workoutDaysLast7").value(2))
                .andExpect(jsonPath("$.latestWeighIn.date").value("2026-11-20"))
                .andExpect(jsonPath("$.weightLast30.length()").value(3))
                .andExpect(jsonPath("$.weightLast30[0].date").value("2026-10-22"))
                .andExpect(jsonPath("$.weightLast30[2].date").value("2026-11-20"))
                .andExpect(jsonPath("$.weekAvgCaloriesPerLoggedDay").value(1700.0))
                .andExpect(jsonPath("$.weekAvgProteinPerLoggedDay").value(117.6))
                .andExpect(jsonPath("$.recentActivity.length()").value(7))
                .andExpect(jsonPath("$.recentActivity[0].kind").value("meal"))
                .andExpect(jsonPath("$.recentActivity[0].title").value("Future"));
    }

    @Test
    void emptyDatabaseGivesZeroesAndNoWeighIn() throws Exception {
        mvc.perform(get("/api/dashboard").cookie(signIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestWeighIn").isEmpty())
                .andExpect(jsonPath("$.weekAvgCaloriesPerLoggedDay").value(0.0))
                .andExpect(jsonPath("$.recentActivity.length()").value(0));
    }

    private void meal(String date, String food, double calories, double protein, String createdAt) {
        jdbc.update("INSERT INTO meals (meal_date, food, calories, protein_g, created_at) VALUES (?, ?, ?, ?, ?)",
                date, food, calories, protein, createdAt);
    }

    private void workout(String date, String createdAt) {
        jdbc.update("INSERT INTO workouts (workout_date, created_at) VALUES (?, ?)", date, createdAt);
    }

    private void weighIn(String date, double weight, String createdAt) {
        jdbc.update("INSERT INTO weigh_ins (weigh_date, weight_kg, created_at) VALUES (?, ?, ?)",
                date, weight, createdAt);
    }
}
