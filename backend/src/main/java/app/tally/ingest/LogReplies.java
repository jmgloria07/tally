package app.tally.ingest;

import app.tally.common.Numbers;
import app.tally.meal.MealDto;
import app.tally.weighin.WeighInDto;
import app.tally.workout.WorkoutDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Reply texts (discord-ingest spec §3.3, E15). The ✅ reply echoes what was logged in the strict format, so it
 * can be copied, fixed and reposted; {@code LogRepliesTest} checks that it parses back to the same entries.
 */
public final class LogReplies {

    /** Discord's message length limit. */
    public static final int MAX_LENGTH = 2000;

    public static final String ALREADY_LOGGED = "Already logged.";
    public static final String HELP_HINT = "Send `help` for the format.";

    /** A complete message using every entry type and the main set forms. A test requires it to parse. */
    public static final String HELP_EXAMPLE = """
            meal: Oats with whey | 420 | 32/60/8 | breakfast
            meal: Chicken and rice | 650 | 45/80/12
            lift: Squat | 3x5@100 | felt strong
            lift: Bench press | 5x70, 5x70, 4x72.5
            lift: Overhead press | 5,5,4@40
            lift: Pull-up | 8,8,6
            weight: 72.4 | BF: 17.5%
            note: Legs and push""";

    /** The canonical cheat-sheet (E15); the README copies it. */
    public static final String HELP = """
            **Log format**: one entry per line
            `meal: food | kcal | p/c/f | notes`
            `lift: exercise | sets | notes`
            `weight: kg | notes`
            `note: workout notes`
            `date: YYYY-MM-DD` (optional, first line; default is the day you post)

            **Sets**
            `5x100`: 5 reps at 100 kg
            `3x5@100`: 3 sets of 5 at 100 kg
            `5,5,4@70`: these reps, all at 70 kg
            `8,8,6`: bodyweight

            Macros and notes are optional. Weights are in kg. All lift lines in a message make one workout. \
            Start a message with `//` to have it ignored.

            **Example**
            ```
            %s
            ```""".formatted(HELP_EXAMPLE);

    private LogReplies() {
    }

    public static String invalid(String reason) {
        return reason + "\n" + HELP_HINT;
    }

    /**
     * The ✅ reply: the logged entries in the strict format inside a code block. If it would exceed Discord's
     * limit, trailing lines are left out and counted; the data itself is always logged in full.
     */
    public static String logged(ParsedLog log) {
        List<String> lines = entryLines(log);
        String header = "Logged:\n```\ndate: " + log.date() + "\n";
        for (int shown = lines.size(); shown >= 0; shown--) {
            int omitted = lines.size() - shown;
            StringBuilder reply = new StringBuilder(header);
            for (int i = 0; i < shown; i++) {
                reply.append(lines.get(i)).append('\n');
            }
            reply.append("```");
            if (omitted > 0) {
                reply.append("\n…and ").append(omitted).append(omitted == 1 ? " more line" : " more lines")
                        .append(" not shown. Everything was logged.");
            }
            if (reply.length() <= MAX_LENGTH || shown == 0) {
                return reply.toString();
            }
        }
        throw new IllegalStateException("unreachable");
    }

    static List<String> entryLines(ParsedLog log) {
        List<String> lines = new ArrayList<>();
        for (MealDto.Request meal : log.meals()) {
            lines.add(mealLine(meal));
        }
        if (log.workout() != null) {
            for (WorkoutDto.ExerciseRequest exercise : log.workout().exercises()) {
                lines.add("lift: " + exercise.name() + " | " + sets(exercise.sets()) + notes(exercise.notes()));
            }
        }
        for (WeighInDto.Request weighIn : log.weighIns()) {
            lines.add("weight: " + Numbers.canonical(weighIn.weightKg()) + notes(weighIn.notes()));
        }
        if (log.workout() != null && !log.workout().notes().isEmpty()) {
            lines.add("note: " + log.workout().notes());
        }
        return lines;
    }

    /** Macros are written when any is set, or when notes follow (so the notes aren't read as macros). */
    private static String mealLine(MealDto.Request meal) {
        StringBuilder line = new StringBuilder("meal: ").append(meal.food()).append(" | ")
                .append(Numbers.canonical(meal.calories()));
        boolean hasMacros = meal.proteinG() > 0 || meal.carbsG() > 0 || meal.fatG() > 0;
        if (hasMacros || !meal.notes().isEmpty()) {
            line.append(" | ").append(Numbers.canonical(meal.proteinG())).append('/')
                    .append(Numbers.canonical(meal.carbsG())).append('/').append(Numbers.canonical(meal.fatG()));
        }
        return line.append(notes(meal.notes())).toString();
    }

    /**
     * Consecutive identical weighted sets become {@code 3x5@100}, a single one {@code 5x100}; bodyweight sets
     * are listed as bare reps. Every form parses back to the same sets.
     */
    static String sets(List<WorkoutDto.SetRequest> sets) {
        List<String> groups = new ArrayList<>();
        int i = 0;
        while (i < sets.size()) {
            WorkoutDto.SetRequest set = sets.get(i);
            String reps = Numbers.canonical(set.reps());
            if (set.weightKg() == 0) {
                groups.add(reps);
                i++;
                continue;
            }
            int run = 1;
            while (i + run < sets.size() && sets.get(i + run).equals(set)) {
                run++;
            }
            String weight = Numbers.canonical(set.weightKg());
            groups.add(run == 1 ? reps + "x" + weight : run + "x" + reps + "@" + weight);
            i += run;
        }
        return String.join(", ", groups);
    }

    private static String notes(String notes) {
        return notes.isEmpty() ? "" : " | " + notes;
    }
}
