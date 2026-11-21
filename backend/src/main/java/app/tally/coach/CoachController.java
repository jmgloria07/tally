package app.tally.coach;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CoachController {

    private final CoachService coachService;
    private final LmStudioClient lmStudioClient;

    public CoachController(CoachService coachService, LmStudioClient lmStudioClient) {
        this.coachService = coachService;
        this.lmStudioClient = lmStudioClient;
    }

    @PostMapping(path = "/api/coach", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CoachDto.Response coach(@RequestBody CoachDto.Request request) {
        return new CoachDto.Response(coachService.answer(request));
    }

    /**
     * The exact system prompt and tracker block the coach would receive now, so the user can check what the
     * model read. Same data the user can already see; never logged.
     */
    @GetMapping("/api/coach/context")
    public CoachDto.ContextResponse context() {
        CoachContextBuilder.CoachContext context = coachService.context();
        return new CoachDto.ContextResponse(context.systemPrompt(), context.trackerBlock());
    }

    /** LM Studio status for the UI's status pill. Always 200. */
    @GetMapping("/api/health")
    public CoachDto.HealthResponse health() {
        LmStudioClient.Health health = lmStudioClient.health();
        return new CoachDto.HealthResponse(health.online() ? "online" : "offline", health.model());
    }
}
