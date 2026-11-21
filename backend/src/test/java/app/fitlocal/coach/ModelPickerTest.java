package app.fitlocal.coach;

import static org.assertj.core.api.Assertions.assertThat;

import app.fitlocal.coach.ModelPicker.LmModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelPickerTest {

    private static final LmModel BIG_NOT_LOADED = new LmModel("qwen/qwen3.8-27b", "vlm", false);
    private static final LmModel QWEN_LOADED = new LmModel("qwen3-14b", "llm", true);
    private static final LmModel COACH_LOADED = new LmModel("fitlocal-coach", "llm", true);
    private static final LmModel EMBEDDINGS_LOADED = new LmModel("text-embedding-nomic", "embeddings", true);

    @Test
    void picksTheLoadedModelNotTheFirstDownloadedOne() {
        assertThat(ModelPicker.pick("", List.of(BIG_NOT_LOADED, QWEN_LOADED))).contains("qwen3-14b");
    }

    @Test
    void prefersTheConfiguredModelWhenItIsLoaded() {
        assertThat(ModelPicker.pick("fitlocal-coach", List.of(QWEN_LOADED, COACH_LOADED))).contains("fitlocal-coach");
    }

    @Test
    void usesAnotherLoadedModelWhenTheConfiguredOneIsNotLoaded() {
        assertThat(ModelPicker.pick("fitlocal-coach", List.of(BIG_NOT_LOADED, QWEN_LOADED))).contains("qwen3-14b");
    }

    @Test
    void loadedVisionModelsCanChat() {
        assertThat(ModelPicker.pick(null, List.of(new LmModel("qwen/qwen3.8-27b", "vlm", true))))
                .contains("qwen/qwen3.8-27b");
    }

    @Test
    void nothingWhenNoChatModelIsLoaded() {
        assertThat(ModelPicker.pick("fitlocal-coach", List.of(BIG_NOT_LOADED, EMBEDDINGS_LOADED))).isEmpty();
        assertThat(ModelPicker.pick("", List.of())).isEmpty();
    }
}
