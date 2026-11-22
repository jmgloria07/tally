package app.tally.ingest;

import app.tally.meal.MealDto;
import app.tally.weighin.WeighInDto;
import app.tally.workout.WorkoutDto;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything one message logs, already validated by the request DTOs (see E5). {@code workout} is null when the
 * message has no {@code lift:} lines. At least one entry is always present.
 */
public record ParsedLog(LocalDate date, List<MealDto.Request> meals, WorkoutDto.Request workout,
        List<WeighInDto.Request> weighIns) {

    public ParsedLog {
        meals = List.copyOf(meals);
        weighIns = List.copyOf(weighIns);
    }

    /** Meals, exercises and weigh-ins: one per log line, not counting {@code date:} or {@code note:}. */
    public int entryCount() {
        return meals.size() + weighIns.size() + (workout == null ? 0 : workout.exercises().size());
    }
}
