package app.fitlocal.settings;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsDto.Response get() {
        return SettingsDto.Response.from(settingsService.current());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public SettingsDto.Response replace(@RequestBody SettingsDto.Request request) {
        return SettingsDto.Response.from(settingsService.replace(request));
    }
}
