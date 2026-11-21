package app.tally.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.tally.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkoutApiTests extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TWO_EXERCISES = """
            {"workoutDate": "2026-11-18", "notes": "Felt strong",
             "exercises": [
               {"name": "Incline DB Press", "notes": "Hard sets",
                "sets": [{"reps": 8, "weightKg": 17.5}, {"reps": 8, "weightKg": 17.5}, {"reps": 7, "weightKg": 15}]},
               {"name": "Hanging Knee Raise", "notes": "",
                "sets": [{"reps": 12}, {"reps": 10, "weightKg": 0}]}
             ]}""";

    private Cookie cookie;

    @BeforeEach
    void signInFirst() throws Exception {
        cookie = signIn();
    }

    @Test
    void createThenListRoundTripsStructureAndOrder() throws Exception {
        JsonNode created = body(postWorkout(TWO_EXERCISES).andExpect(status().isCreated()));
        assertThat(created.get("id").asLong()).isPositive();
        assertThat(created.get("createdAt").asString()).isEqualTo("2026-11-20T12:00:00");

        JsonNode listed = body(mvc.perform(get("/api/workouts").cookie(cookie)).andExpect(status().isOk())).get(0);
        assertThat(listed.get("workoutDate").asString()).isEqualTo("2026-11-18");
        assertThat(listed.get("notes").asString()).isEqualTo("Felt strong");
        JsonNode press = listed.get("exercises").get(0);
        assertThat(press.get("name").asString()).isEqualTo("Incline DB Press");
        assertThat(press.get("notes").asString()).isEqualTo("Hard sets");
        assertThat(press.get("sets")).hasSize(3);
        assertThat(press.get("sets").get(2).get("reps").asInt()).isEqualTo(7);
        assertThat(press.get("sets").get(2).get("weightKg").asDouble()).isEqualTo(15.0);
        JsonNode raise = listed.get("exercises").get(1);
        assertThat(raise.get("name").asString()).isEqualTo("Hanging Knee Raise");
        assertThat(raise.get("sets").get(0).get("weightKg").asDouble()).isZero();

        assertThat(jdbc.queryForList("SELECT position FROM workout_exercises ORDER BY id", Integer.class))
                .containsExactly(1, 2);
        assertThat(jdbc.queryForList("SELECT set_no FROM workout_sets ORDER BY id", Integer.class))
                .containsExactly(1, 2, 3, 1, 2);
    }

    @Test
    void validationErrorsNameTheField() throws Exception {
        postWorkout("{\"workoutDate\": \"2026-11-18\", \"exercises\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Add at least one exercise"));
        postWorkout(oneSet("  ", 8, 10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Exercise name is required (exercise 1)"));
        postWorkout(oneSet("Squat", 0, 10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Reps must be a whole number between 1 and 1000 (exercise 1, set 1)"));
        postWorkout(oneSet("Squat", 8, -1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Weight must be between 0 and 2000 kg (exercise 1, set 1)"));
        postWorkout("{\"workoutDate\": \"not-a-date\", \"exercises\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Date must be a valid date (YYYY-MM-DD)"));
        assertThat(count("workouts")).isZero();
    }

    @Test
    void putReplacesEverythingAndKeepsIdentity() throws Exception {
        JsonNode created = body(postWorkout(TWO_EXERCISES).andExpect(status().isCreated()));
        long id = created.get("id").asLong();
        clock.advance(java.time.Duration.ofHours(1));

        JsonNode replaced = body(mvc.perform(put("/api/workouts/" + id).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneSet("Deadlift", 5, 100).replace("2026-11-18", "2026-11-19")))
                .andExpect(status().isOk()));

        assertThat(replaced.get("id").asLong()).isEqualTo(id);
        assertThat(replaced.get("createdAt").asString()).isEqualTo(created.get("createdAt").asString());
        assertThat(replaced.get("workoutDate").asString()).isEqualTo("2026-11-19");
        assertThat(replaced.get("exercises")).hasSize(1);
        assertThat(count("workouts")).isEqualTo(1);
        assertThat(count("workout_exercises")).isEqualTo(1);
        assertThat(count("workout_sets")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM workout_exercises", String.class)).isEqualTo("Deadlift");

        mvc.perform(put("/api/workouts/99999").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(oneSet("Deadlift", 5, 100)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteCascadesToExercisesAndSets() throws Exception {
        long id = body(postWorkout(TWO_EXERCISES)).get("id").asLong();

        mvc.perform(delete("/api/workouts/" + id).cookie(cookie)).andExpect(status().isNoContent());

        assertThat(count("workouts")).isZero();
        assertThat(count("workout_exercises")).isZero();
        assertThat(count("workout_sets")).isZero();
        mvc.perform(delete("/api/workouts/" + id).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void retriedSubmitWithSameSubmissionIdIsSavedOnce() throws Exception {
        String submissionId = UUID.randomUUID().toString();
        String body = TWO_EXERCISES.replaceFirst("\\{", "{\"submissionId\": \"" + submissionId + "\", ");

        long first = body(postWorkout(body).andExpect(status().isCreated())).get("id").asLong();
        long second = body(postWorkout(body).andExpect(status().isOk())).get("id").asLong();

        assertThat(second).isEqualTo(first);
        assertThat(count("workouts")).isEqualTo(1);
        assertThat(count("workout_exercises")).isEqualTo(2);
    }

    private ResultActions postWorkout(String json) throws Exception {
        return mvc.perform(post("/api/workouts").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String oneSet(String name, double reps, double weight) {
        return "{\"workoutDate\": \"2026-11-18\", \"exercises\": [{\"name\": \"" + name
                + "\", \"sets\": [{\"reps\": " + reps + ", \"weightKg\": " + weight + "}]}]}";
    }

    private static JsonNode body(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
