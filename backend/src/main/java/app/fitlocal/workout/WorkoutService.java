package app.fitlocal.workout;

import app.fitlocal.common.ClockConfig;
import app.fitlocal.common.NotFoundException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkoutService {

    /** Result of a create: {@code created=false} means the submissionId was already saved (a retry). */
    public record CreateResult(WorkoutDto.Response workout, boolean created) {
    }

    private final WorkoutRepository repository;
    private final EntityManager entityManager;
    private final Clock clock;

    public WorkoutService(WorkoutRepository repository, EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WorkoutDto.Response> recent(int limit) {
        return repository.findAllByOrderByWorkoutDateDescIdDesc(PageRequest.of(0, limit)).stream()
                .map(WorkoutDto.Response::from)
                .toList();
    }

    /**
     * Creates a session. If the request carries a submissionId that is already stored, the earlier workout
     * is returned and nothing is inserted (spec C7): the first submit committed but its response was lost.
     */
    @Transactional
    public CreateResult create(WorkoutDto.Request request) {
        if (request.submissionId() != null) {
            Optional<Workout> existing = repository.findBySubmissionId(request.submissionId());
            if (existing.isPresent()) {
                return new CreateResult(WorkoutDto.Response.from(existing.get()), false);
            }
        }
        Workout workout = new Workout(request.date(), request.notes(), request.submissionId(),
                ClockConfig.now(clock));
        addExercises(workout, request.exercises());
        return new CreateResult(WorkoutDto.Response.from(repository.save(workout)), true);
    }

    /**
     * Replaces date, notes, exercises and sets. The old children are removed and flushed before the new ones
     * are added, because Hibernate otherwise orders inserts before deletes within one flush.
     * {@code id}, {@code createdAt} and {@code submissionId} do not change.
     */
    @Transactional
    public WorkoutDto.Response replace(long id, WorkoutDto.Request request) {
        Workout workout = repository.findById(id).orElseThrow(() -> new NotFoundException("Workout not found"));
        workout.setWorkoutDate(request.date());
        workout.setNotes(request.notes());
        workout.getExercises().clear();
        entityManager.flush();
        addExercises(workout, request.exercises());
        entityManager.flush();
        return WorkoutDto.Response.from(workout);
    }

    @Transactional
    public void delete(long id) {
        Workout workout = repository.findById(id).orElseThrow(() -> new NotFoundException("Workout not found"));
        repository.delete(workout);
    }

    private static void addExercises(Workout workout, List<WorkoutDto.ExerciseRequest> exercises) {
        for (WorkoutDto.ExerciseRequest exerciseRequest : exercises) {
            WorkoutExercise exercise = workout.addExercise(exerciseRequest.name(), exerciseRequest.notes());
            for (WorkoutDto.SetRequest set : exerciseRequest.sets()) {
                exercise.addSet(set.reps().intValue(), set.weightKg());
            }
        }
    }
}
