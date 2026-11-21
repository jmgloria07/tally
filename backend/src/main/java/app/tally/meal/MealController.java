package app.tally.meal;

import app.tally.common.Checks;
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
@RequestMapping("/api/meals")
public class MealController {

    private final MealService mealService;

    public MealController(MealService mealService) {
        this.mealService = mealService;
    }

    @GetMapping
    public List<MealDto.Response> list(@RequestParam(required = false) Integer limit) {
        return mealService.recent(Checks.clampLimit(limit, 150));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MealDto.Response> create(@RequestBody MealDto.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mealService.create(request));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MealDto.Response update(@PathVariable long id, @RequestBody MealDto.Request request) {
        return mealService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        mealService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
