package app.fitlocal.meal;

import app.fitlocal.common.ClockConfig;
import app.fitlocal.common.NotFoundException;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MealService {

    private final MealRepository repository;
    private final Clock clock;

    public MealService(MealRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MealDto.Response> recent(int limit) {
        return repository.findAllByOrderByMealDateDescIdDesc(PageRequest.of(0, limit)).stream()
                .map(MealDto.Response::from)
                .toList();
    }

    @Transactional
    public MealDto.Response create(MealDto.Request request) {
        Meal meal = new Meal(ClockConfig.now(clock));
        request.applyTo(meal);
        return MealDto.Response.from(repository.save(meal));
    }

    @Transactional
    public MealDto.Response update(long id, MealDto.Request request) {
        Meal meal = repository.findById(id).orElseThrow(() -> new NotFoundException("Meal not found"));
        request.applyTo(meal);
        return MealDto.Response.from(meal);
    }

    @Transactional
    public void delete(long id) {
        Meal meal = repository.findById(id).orElseThrow(() -> new NotFoundException("Meal not found"));
        repository.delete(meal);
    }
}
