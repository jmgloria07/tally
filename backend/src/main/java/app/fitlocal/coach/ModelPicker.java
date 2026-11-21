package app.fitlocal.coach;

import java.util.List;
import java.util.Optional;

/**
 * Chooses the model for a coach request from what LM Studio has loaded right now (decision D6). Only ever names
 * a loaded model, so a coach question can never make LM Studio load one on its own (its just-in-time loading
 * would otherwise load whatever a request names, for example the first downloaded model in the list).
 */
final class ModelPicker {

    /** One entry from LM Studio's {@code GET /api/v0/models}. {@code type} is llm, vlm, embeddings, … */
    record LmModel(String id, String type, boolean loaded) {

        boolean canChat() {
            return "llm".equals(type) || "vlm".equals(type);
        }
    }

    private ModelPicker() {
    }

    /**
     * The configured model ({@code LM_STUDIO_MODEL}) if it is loaded; otherwise the first loaded chat model.
     * Empty when no chat model is loaded.
     */
    static Optional<String> pick(String configured, List<LmModel> models) {
        List<LmModel> ready = models.stream()
                .filter(m -> m.loaded() && m.canChat() && m.id() != null && !m.id().isBlank())
                .toList();
        String wanted = configured == null ? "" : configured.strip();
        if (!wanted.isEmpty()) {
            for (LmModel m : ready) {
                if (m.id().equals(wanted)) {
                    return Optional.of(m.id());
                }
            }
        }
        return ready.stream().findFirst().map(LmModel::id);
    }
}
