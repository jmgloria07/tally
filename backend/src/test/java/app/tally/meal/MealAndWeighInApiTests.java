package app.tally.meal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.tally.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class MealAndWeighInApiTests extends IntegrationTest {

    private Cookie cookie;

    @BeforeEach
    void signInFirst() throws Exception {
        cookie = signIn();
    }

    @Test
    void mealCrudAndNewestFirstOrder() throws Exception {
        String id = create("/api/meals", "{\"mealDate\":\"2026-11-19\",\"food\":\" Oats \",\"calories\":420,"
                + "\"proteinG\":32,\"notes\":\"1 scoop\"}");
        create("/api/meals", "{\"mealDate\":\"2026-11-20\",\"food\":\"Rice\",\"calories\":600}");

        mvc.perform(get("/api/meals").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].food").value("Rice"))
                .andExpect(jsonPath("$[0].carbsG").value(0.0))
                .andExpect(jsonPath("$[1].food").value("Oats"))
                .andExpect(jsonPath("$[1].createdAt").value("2026-11-20T12:00:00"));

        mvc.perform(put("/api/meals/" + id).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mealDate\":\"2026-11-19\",\"food\":\"Oatmeal\",\"calories\":450}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.food").value("Oatmeal"))
                .andExpect(jsonPath("$.notes").value(""));

        mvc.perform(delete("/api/meals/" + id).cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/meals/" + id).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void mealValidation() throws Exception {
        mvc.perform(post("/api/meals").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mealDate\":\"2026-11-19\",\"food\":\"\",\"calories\":420}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Food is required"));
        mvc.perform(post("/api/meals").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mealDate\":\"2026-11-19\",\"food\":\"Oats\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Calories is required"));
        mvc.perform(post("/api/meals").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mealDate\":\"2026-11-19\",\"food\":\"Oats\",\"calories\":10,\"fatG\":1001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Fat must be between 0 and 1000"));
        mvc.perform(post("/api/meals").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid JSON request body"));
    }

    @Test
    void weighInCrudAndValidation() throws Exception {
        String id = create("/api/weigh-ins", "{\"weighDate\":\"2026-11-20\",\"weightKg\":72.4,\"notes\":\"BF: 17.5%\"}");
        mvc.perform(put("/api/weigh-ins/" + id).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weighDate\":\"2026-11-20\",\"weightKg\":72.1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(72.1));
        mvc.perform(post("/api/weigh-ins").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weighDate\":\"2026-11-20\",\"weightKg\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Weight must be between 20 and 500"));
        mvc.perform(delete("/api/weigh-ins/" + id).cookie(cookie)).andExpect(status().isNoContent());
    }

    private String create(String path, String json) throws Exception {
        String body = mvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll("^\\{\"id\":(\\d+),.*$", "$1");
    }
}
