package app.tally.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.tally.meal.MealDto;
import app.tally.workout.WorkoutDto;
import app.tally.workout.WorkoutDto.SetRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class StrictLogParserTest {

    private static final MessageContext SENT = new MessageContext(LocalDateTime.of(2026, 11, 22, 8, 30),
            ZoneId.of("Asia/Manila"));

    private final StrictLogParser parser = new StrictLogParser();

    private ParsedLog parse(String text) {
        return parser.parse(text, SENT);
    }

    private void assertInvalid(String text, String message) {
        assertThatThrownBy(() -> parse(text)).isInstanceOf(InvalidLogException.class).hasMessage(message);
    }

    private static SetRequest set(double reps, double weightKg) {
        return new SetRequest(reps, weightKg);
    }

    @Test
    void helpExampleParses() {
        ParsedLog log = parse(LogReplies.HELP_EXAMPLE);
        assertThat(log.meals()).hasSize(2);
        assertThat(log.workout().exercises()).hasSize(4);
        assertThat(log.weighIns()).hasSize(1);
        assertThat(log.workout().notes()).isEqualTo("Legs and push");
    }

    @Test
    void mixedMessageDatedByTheMessageByDefault() {
        ParsedLog log = parse("""
                MEAL: Oats | 420 | 32/60/8 | breakfast
                  meal:Rice|205

                weight: 72.4 kg | BF: 17.5%
                """);
        assertThat(log.date()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(log.workout()).isNull();
        assertThat(log.meals()).containsExactly(
                new MealDto.Request("2026-11-22", "Oats", 420.0, 32.0, 60.0, 8.0, "breakfast"),
                new MealDto.Request("2026-11-22", "Rice", 205.0, 0.0, 0.0, 0.0, ""));
        assertThat(log.weighIns().get(0).weightKg()).isEqualTo(72.4);
        assertThat(log.weighIns().get(0).notes()).isEqualTo("BF: 17.5%");
        assertThat(log.entryCount()).isEqualTo(3);
    }

    @Test
    void mealNotesWithoutMacrosAndKcalSuffix() {
        MealDto.Request meal = parse("meal: Rice 1 cup | 205kcal | lunch | from the canteen").meals().get(0);
        assertThat(meal.calories()).isEqualTo(205.0);
        assertThat(meal.proteinG()).isZero();
        assertThat(meal.notes()).isEqualTo("lunch | from the canteen");
    }

    @Test
    void aMalformedMacrosFieldFailsInsteadOfBecomingNotes() {
        for (String macros : new String[] {"30/40", "30/40/", "/", "30 / 40 / 5 / 1"}) {
            assertInvalid("meal: Oats | 420 | " + macros,
                    "Line 1: macros must be protein/carbs/fat in grams, like 32/60/8");
        }
        // Words make it notes, as before.
        assertThat(parse("meal: Pizza | 600 | 1/2 of a large").meals().get(0).notes()).isEqualTo("1/2 of a large");
    }

    @Test
    void dateLineOverridesTheMessageDate() {
        ParsedLog log = parse("date: 2026-11-21\nmeal: Oats | 420");
        assertThat(log.date()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(log.meals().get(0).mealDate()).isEqualTo("2026-11-21");
    }

    @Test
    void dateRules() {
        assertInvalid("date: 2026-11-23\nmeal: Oats | 420",
                "Line 1: date can't be after the day the message was sent (2026-11-22)");
        assertInvalid("meal: Oats | 420\ndate: 2026-11-21", "Line 2: date: must be the first line, and only once");
        assertInvalid("date: 2026-11-21\ndate: 2026-11-20", "Line 2: date: must be the first line, and only once");
        assertInvalid("date: yesterday\nmeal: Oats | 420", "Line 1: Date must be a valid date (YYYY-MM-DD)");
        assertInvalid("date: 2026-11-21", "Nothing to log.");
    }

    @Test
    void allLiftLinesFormOneWorkoutInOrder() {
        WorkoutDto.Request workout = parse("""
                lift: Squat | 3x5@100 | knees ok
                lift: Squat | 1x3@110
                note: heavy day
                """).workout();
        assertThat(workout.workoutDate()).isEqualTo("2026-11-22");
        assertThat(workout.notes()).isEqualTo("heavy day");
        assertThat(workout.submissionId()).isNull();
        assertThat(workout.exercises()).extracting(WorkoutDto.ExerciseRequest::name).containsExactly("Squat", "Squat");
        assertThat(workout.exercises().get(0).notes()).isEqualTo("knees ok");
        assertThat(workout.exercises().get(0).sets()).containsExactly(set(5, 100), set(5, 100), set(5, 100));
        assertThat(workout.exercises().get(1).sets()).containsExactly(set(3, 110));
    }

    @Test
    void everySetForm() {
        assertThat(StrictLogParser.parseSets("5x100, 5 X 100kg, 3x110", 1))
                .containsExactly(set(5, 100), set(5, 100), set(3, 110));
        assertThat(StrictLogParser.parseSets("2 x 5 @ 72.5 kg", 1)).containsExactly(set(5, 72.5), set(5, 72.5));
        assertThat(StrictLogParser.parseSets("5,5,4@70", 1)).containsExactly(set(5, 70), set(5, 70), set(4, 70));
        assertThat(StrictLogParser.parseSets("8, 8, 6", 1)).containsExactly(set(8, 0), set(8, 0), set(6, 0));
        // bare reps before an RxW group are bodyweight; the @ only reaches back to the previous group
        assertThat(StrictLogParser.parseSets("10, 5x20, 6,6@30", 1))
                .containsExactly(set(10, 0), set(5, 20), set(6, 30), set(6, 30));
    }

    @Test
    void setErrors() {
        assertInvalid("lift: Squat | 5x225lb", "Line 1: weights are in kg only; convert lb to kg");
        assertInvalid("lift: Squat | 5x100,,5x100", "Line 1: empty set in \"5x100,,5x100\"");
        assertInvalid("lift: Squat | heavy", "Line 1: can't read the set \"heavy\". Try 5x100, 3x5@100, 5,5,4@70 or 8,8,6");
        assertInvalid("lift: Squat | 51x5@100", "Line 1: set count must be between 1 and 50 in \"51x5@100\"");
        assertInvalid("lift: Squat", "Line 1: lift: needs at least exercise | sets");
    }

    @Test
    void workoutValidationNamesTheLine() {
        assertInvalid("meal: Oats | 420\nlift: Squat | 5x100\nlift: Bench | 0x60",
                "Line 3: Reps must be a whole number between 1 and 1000 (set 1)");
        assertInvalid("lift: Squat | 5x2001", "Line 1: Weight must be between 0 and 2000 kg (set 1)");
        assertInvalid("lift:  | 5x100", "Line 1: Exercise name is required");
        assertInvalid("lift: Squat | 5x100\nnote: " + "x".repeat(1001),
                "Line 2: Workout notes must be 1000 characters or fewer");
        assertInvalid("lift: Squat | " + "1,".repeat(50) + "1",
                "Line 1: An exercise can have at most 50 sets");
    }

    @Test
    void mealAndWeighInValidation() {
        assertInvalid("meal: Oats", "Line 1: meal: needs at least food | kcal");
        assertInvalid("meal: Oats | lots", "Line 1: Calories must be a number, not \"lots\"");
        assertInvalid("meal:  | 420", "Line 1: Food is required");
        assertInvalid("meal: Oats | 420 | 1001/0/0", "Line 1: Protein must be between 0 and 1000");
        assertInvalid("weight: 160 lbs", "Line 1: weights are in kg only; convert lb to kg");
        assertInvalid("weight: 10", "Line 1: Weight must be between 20 and 500");
        assertInvalid("weight:", "Line 1: Weight must be a number, not \"\"");
    }

    @Test
    void lineErrors() {
        assertInvalid("meal: Oats | 420\nchicken and rice",
                "Line 2: expected meal:, lift:, weight:, note: or date: at the start of the line");
        assertInvalid("\n\nsnack: chips | 150", "Line 3: unknown entry \"snack:\". Use meal:, lift:, weight:, note: or date:");
        assertInvalid("note: good day", "Line 1: note: is for workouts; add at least one lift: line");
        assertInvalid("lift: Squat | 5x100\nnote: a\nnote: b", "Line 3: only one note: line per message");
        assertInvalid("   \n  ", "Nothing to log.");
    }

    @Test
    void manyExercisesFailAsAWhole() {
        String text = "lift: Squat | 5x100\n".repeat(WorkoutDto.MAX_EXERCISES + 1);
        assertInvalid(text, "A workout can have at most 50 exercises");
    }
}
