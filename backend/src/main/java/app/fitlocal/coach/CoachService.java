package app.fitlocal.coach;

import app.fitlocal.coach.CoachContextBuilder.CoachContext;
import app.fitlocal.common.ClockConfig;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * One chat turn. Deliberately not transactional: the context is read in its own short transaction, which
 * has ended (and released the single SQLite connection) before the up-to-3-minute LM Studio call (spec C12).
 */
@Service
public class CoachService {

    private final CoachContextBuilder contextBuilder;
    private final LmStudioClient lmStudioClient;
    private final Clock clock;

    public CoachService(CoachContextBuilder contextBuilder, LmStudioClient lmStudioClient, Clock clock) {
        this.contextBuilder = contextBuilder;
        this.lmStudioClient = lmStudioClient;
        this.clock = clock;
    }

    public String answer(CoachDto.Request request) {
        CoachContext context = context();
        List<ChatMessage> messages = assemble(context, CoachHistory.trim(request.history()), request.message());
        return lmStudioClient.chat(messages);
    }

    /** What the model would receive right now (also shown in the Coach tab's "View context" panel). */
    public CoachContext context() {
        return contextBuilder.build(ClockConfig.now(clock));
    }

    /**
     * {@code [system, ...history, user]}. Only the newest user message carries tracker data; history holds
     * the raw questions and answers, so each turn sends exactly one fresh copy of the data.
     */
    static List<ChatMessage> assemble(CoachContext context, List<ChatMessage> history, String message) {
        List<ChatMessage> messages = new ArrayList<>(history.size() + 2);
        messages.add(new ChatMessage("system", context.systemPrompt()));
        messages.addAll(history);
        messages.add(new ChatMessage("user", context.trackerBlock() + "\n\nUSER QUESTION\n" + message));
        return messages;
    }
}
