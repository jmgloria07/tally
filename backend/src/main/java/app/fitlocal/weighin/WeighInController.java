package app.fitlocal.weighin;

import app.fitlocal.common.Checks;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weigh-ins")
public class WeighInController {

    private final WeighInService weighInService;

    public WeighInController(WeighInService weighInService) {
        this.weighInService = weighInService;
    }

    @GetMapping
    public List<WeighInDto.Response> list(@RequestParam(required = false) Integer limit) {
        return weighInService.recent(Checks.clampLimit(limit, 100));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WeighInDto.Response> create(@RequestBody WeighInDto.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(weighInService.create(request));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WeighInDto.Response update(@PathVariable long id, @RequestBody WeighInDto.Request request) {
        return weighInService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        weighInService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
