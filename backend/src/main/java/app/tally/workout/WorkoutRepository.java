package app.tally.workout;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutRepository extends JpaRepository<Workout, Long> {

    List<Workout> findAllByOrderByWorkoutDateDescIdDesc(Pageable page);

    List<Workout> findByWorkoutDateBetweenOrderByWorkoutDateDescIdDesc(LocalDate from, LocalDate to);

    Optional<Workout> findBySubmissionId(String submissionId);

    Optional<Workout> findFirstByWorkoutDateBeforeOrderByWorkoutDateDescIdDesc(LocalDate date);
}
