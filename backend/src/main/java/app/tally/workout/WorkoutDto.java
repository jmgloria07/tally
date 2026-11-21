package app.tally.workout;

import app.tally.common.Checks;
import app.tally.common.IsoFormats;
import app.tally.common.ValidationException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * API shapes for {@code /api/workouts}. Exercise and set order is the array order; the server assigns
 * {@code position} and {@code setNo} from it (1-based).
 */
public final class WorkoutDto {

    static final int MAX_EXERCISES = 50;
    static final int MAX_SETS = 50;

    private WorkoutDto() {
    }

    public record SetResponse(int reps, double weightKg) {
    }

    public record ExerciseResponse(String name, String notes, List<SetResponse> sets) {
    }

    public record Response(Long id, String workoutDate, String notes, String createdAt,
            List<ExerciseResponse> exercises) {

        public static Response from(Workout w) {
            List<ExerciseResponse> exercises = w.getExercises().stream()
                    .map(e -> new ExerciseResponse(e.getName(), e.getNotes(), e.getSets().stream()
                            .map(s -> new SetResponse(s.getReps(), s.getWeightKg()))
                            .toList()))
                    .toList();
            return new Response(w.getId(), IsoFormats.date(w.getWorkoutDate()), w.getNotes(),
                    IsoFormats.dateTime(w.getCreatedAt()), exercises);
        }
    }

    public record SetRequest(Double reps, Double weightKg) {
    }

    public record ExerciseRequest(String name, String notes, List<SetRequest> sets) {
    }

    /**
     * Create/replace body. The whole tree is validated here, top-down, so messages can say which exercise
     * and set is wrong (spec §7.3). Children are replaced with trimmed, normalized copies.
     */
    public record Request(String workoutDate, String notes, String submissionId, List<ExerciseRequest> exercises) {

        public Request {
            Checks.date(workoutDate, "Date");
            workoutDate = workoutDate.strip();
            notes = Checks.text(notes, "Workout notes", 1000, false);
            submissionId = Checks.optionalUuid(submissionId, "Submission id");
            if (exercises == null || exercises.isEmpty()) {
                throw new ValidationException("Add at least one exercise");
            }
            if (exercises.size() > MAX_EXERCISES) {
                throw new ValidationException("A workout can have at most " + MAX_EXERCISES + " exercises");
            }
            List<ExerciseRequest> normalized = new ArrayList<>(exercises.size());
            for (int i = 0; i < exercises.size(); i++) {
                normalized.add(normalizeExercise(exercises.get(i), i + 1));
            }
            exercises = List.copyOf(normalized);
        }

        LocalDate date() {
            return LocalDate.parse(workoutDate);
        }

        private static ExerciseRequest normalizeExercise(ExerciseRequest exercise, int number) {
            String where = " (exercise " + number + ")";
            if (exercise == null) {
                throw new ValidationException("Exercise name is required" + where);
            }
            String name = exercise.name() == null ? "" : exercise.name().strip();
            if (name.isEmpty()) {
                throw new ValidationException("Exercise name is required" + where);
            }
            if (name.length() > 100) {
                throw new ValidationException("Exercise name must be 100 characters or fewer" + where);
            }
            String notes = exercise.notes() == null ? "" : exercise.notes().strip();
            if (notes.length() > 500) {
                throw new ValidationException("Exercise notes must be 500 characters or fewer" + where);
            }
            List<SetRequest> sets = exercise.sets();
            if (sets == null || sets.isEmpty()) {
                throw new ValidationException("Add at least one set" + where);
            }
            if (sets.size() > MAX_SETS) {
                throw new ValidationException("An exercise can have at most " + MAX_SETS + " sets" + where);
            }
            List<SetRequest> normalizedSets = new ArrayList<>(sets.size());
            for (int s = 0; s < sets.size(); s++) {
                SetRequest set = sets.get(s);
                String setWhere = " (exercise " + number + ", set " + (s + 1) + ")";
                Double reps = set == null ? null : set.reps();
                Double weight = set == null ? null : set.weightKg();
                int validReps = Checks.wholeNumber(reps, "Reps", 1, 1000, setWhere);
                double validWeight = weight == null ? 0 : weight;
                if (Double.isNaN(validWeight) || validWeight < 0 || validWeight > 2000) {
                    throw new ValidationException("Weight must be between 0 and 2000 kg" + setWhere);
                }
                normalizedSets.add(new SetRequest((double) validReps, validWeight));
            }
            return new ExerciseRequest(name, notes, List.copyOf(normalizedSets));
        }
    }
}
