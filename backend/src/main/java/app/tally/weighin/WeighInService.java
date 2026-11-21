package app.tally.weighin;

import app.tally.common.ClockConfig;
import app.tally.common.NotFoundException;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeighInService {

    private final WeighInRepository repository;
    private final Clock clock;

    public WeighInService(WeighInRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WeighInDto.Response> recent(int limit) {
        return repository.findAllByOrderByWeighDateDescIdDesc(PageRequest.of(0, limit)).stream()
                .map(WeighInDto.Response::from)
                .toList();
    }

    @Transactional
    public WeighInDto.Response create(WeighInDto.Request request) {
        WeighIn weighIn = new WeighIn(ClockConfig.now(clock));
        request.applyTo(weighIn);
        return WeighInDto.Response.from(repository.save(weighIn));
    }

    @Transactional
    public WeighInDto.Response update(long id, WeighInDto.Request request) {
        WeighIn weighIn = repository.findById(id).orElseThrow(() -> new NotFoundException("Weigh-in not found"));
        request.applyTo(weighIn);
        return WeighInDto.Response.from(weighIn);
    }

    @Transactional
    public void delete(long id) {
        WeighIn weighIn = repository.findById(id).orElseThrow(() -> new NotFoundException("Weigh-in not found"));
        repository.delete(weighIn);
    }
}
