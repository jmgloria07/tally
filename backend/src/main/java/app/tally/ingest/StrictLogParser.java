package app.tally.ingest;

import app.tally.common.Checks;
import app.tally.common.ValidationException;
import app.tally.meal.MealDto;
import app.tally.weighin.WeighInDto;
import app.tally.workout.WorkoutDto;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The strict line format (discord-ingest spec §4). One entry per line, fields separated by {@code |}; any line
 * that doesn't match fails the whole message. All value checks are left to the request DTOs, so Discord and
 * the web app enforce the same rules (E5).
 */
@Component
public class StrictLogParser implements LogParser {

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final String NUMBER = "(\\d+(?:\\.\\d+)?)";
    private static final String OPTIONAL_KG = "\\s*(?:kg)?";

    private static final Pattern LINE = Pattern.compile("^([A-Za-z]+)\\s*:(.*)$");
    private static final Pattern CALORIES = Pattern.compile("^" + NUMBER + "\\s*(?:kcal)?$", CI);
    private static final Pattern KILOGRAMS = Pattern.compile("^" + NUMBER + OPTIONAL_KG + "$", CI);
    private static final Pattern MACROS = Pattern.compile("^" + NUMBER + "\\s*/\\s*" + NUMBER + "\\s*/\\s*" + NUMBER + "$");
    /** Only digits, dots, spaces and at least one slash: meant as macros even if malformed. */
    private static final Pattern MACROS_ATTEMPT = Pattern.compile("^[\\d.\\s]*/[\\d.\\s/]*$");
    private static final Pattern POUNDS = Pattern.compile("\\d\\s*lbs?\\b", CI);

    /** {@code 3x5@100}: N sets of R reps at W kg. */
    private static final Pattern SETS_OF_REPS_AT = Pattern.compile("^(\\d+)\\s*x\\s*(\\d+)\\s*@\\s*" + NUMBER + OPTIONAL_KG + "$", CI);
    /** {@code 5x100}: one set of R reps at W kg. */
    private static final Pattern REPS_X_WEIGHT = Pattern.compile("^(\\d+)\\s*x\\s*" + NUMBER + OPTIONAL_KG + "$", CI);
    /** {@code 4@70}: the last of a {@code 5,5,4@70} list; the weight applies to the pending reps too. */
    private static final Pattern REPS_AT = Pattern.compile("^(\\d+)\\s*@\\s*" + NUMBER + OPTIONAL_KG + "$", CI);
    /** {@code 8}: reps with no weight yet; bodyweight unless a later {@code @W} picks them up. */
    private static final Pattern BARE_REPS = Pattern.compile("^(\\d+)$");

    private static final Pattern EXERCISE_REF = Pattern.compile("\\s*\\(exercise (\\d+)(?:, (set \\d+))?\\)");

    @Override
    public ParsedLog parse(String text, MessageContext context) {
        String[] lines = (text == null ? "" : text).split("\\R", -1);
        LocalDate date = context.date();
        boolean seenEntry = false;
        List<MealDto.Request> meals = new ArrayList<>();
        List<WeighInDto.Request> weighIns = new ArrayList<>();
        List<WorkoutDto.ExerciseRequest> exercises = new ArrayList<>();
        List<Integer> exerciseLines = new ArrayList<>();
        String workoutNotes = null;
        int noteLine = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                throw invalid(lineNo, "expected meal:, lift:, weight:, note: or date: at the start of the line");
            }
            String keyword = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2).strip();
            switch (keyword) {
                case "date" -> {
                    if (seenEntry) {
                        throw invalid(lineNo, "date: must be the first line, and only once");
                    }
                    date = parseDate(value, lineNo, context);
                }
                case "meal" -> meals.add(parseMeal(value, lineNo, date));
                case "lift" -> {
                    exercises.add(parseLift(value, lineNo));
                    exerciseLines.add(lineNo);
                }
                case "weight" -> weighIns.add(parseWeighIn(value, lineNo, date));
                case "note" -> {
                    if (workoutNotes != null) {
                        throw invalid(lineNo, "only one note: line per message");
                    }
                    workoutNotes = value;
                    noteLine = lineNo;
                }
                default -> throw invalid(lineNo, "unknown entry \"" + m.group(1) + ":\". "
                        + "Use meal:, lift:, weight:, note: or date:");
            }
            seenEntry = true;
        }

        if (workoutNotes != null && exercises.isEmpty()) {
            throw invalid(noteLine, "note: is for workouts; add at least one lift: line");
        }
        WorkoutDto.Request workout = exercises.isEmpty() ? null
                : buildWorkout(date, workoutNotes, noteLine, exercises, exerciseLines);
        if (meals.isEmpty() && weighIns.isEmpty() && workout == null) {
            throw new InvalidLogException("Nothing to log.");
        }
        return new ParsedLog(date, meals, workout, weighIns);
    }

    private static LocalDate parseDate(String value, int lineNo, MessageContext context) {
        LocalDate date = validated(lineNo, () -> Checks.date(value, "Date"));
        if (date.isAfter(context.date())) { // see E10
            throw invalid(lineNo, "date can't be after the day the message was sent (" + context.date() + ")");
        }
        return date;
    }

    /** {@code meal: food | kcal [| p/c/f] [| notes]}. */
    private static MealDto.Request parseMeal(String value, int lineNo, LocalDate date) {
        List<String> fields = fields(value);
        if (fields.size() < 2) {
            throw invalid(lineNo, "meal: needs at least food | kcal");
        }
        Double calories = number(fields.get(1), CALORIES, lineNo, "Calories");
        Double protein = null;
        Double carbs = null;
        Double fat = null;
        int notesFrom = 2;
        if (fields.size() > 2) {
            Matcher macros = MACROS.matcher(fields.get(2));
            if (macros.matches()) {
                protein = Double.parseDouble(macros.group(1));
                carbs = Double.parseDouble(macros.group(2));
                fat = Double.parseDouble(macros.group(3));
                notesFrom = 3;
            } else if (MACROS_ATTEMPT.matcher(fields.get(2)).matches()) {
                // Numbers and slashes only, e.g. "30/40": a macros typo, not notes (§4: non-matching fails).
                throw invalid(lineNo, "macros must be protein/carbs/fat in grams, like 32/60/8");
            }
        }
        Double p = protein;
        Double c = carbs;
        Double f = fat;
        String notes = rest(fields, notesFrom);
        return validated(lineNo, () -> new MealDto.Request(date.toString(), fields.get(0), calories, p, c, f, notes));
    }

    /** {@code lift: exercise | sets [| notes]}. Validated later, together with the whole workout. */
    private static WorkoutDto.ExerciseRequest parseLift(String value, int lineNo) {
        List<String> fields = fields(value);
        if (fields.size() < 2) {
            throw invalid(lineNo, "lift: needs at least exercise | sets");
        }
        return new WorkoutDto.ExerciseRequest(fields.get(0), rest(fields, 2), parseSets(fields.get(1), lineNo));
    }

    /** {@code weight: kg [| notes]}. */
    private static WeighInDto.Request parseWeighIn(String value, int lineNo, LocalDate date) {
        List<String> fields = fields(value);
        Double kg = number(fields.get(0), KILOGRAMS, lineNo, "Weight");
        return validated(lineNo, () -> new WeighInDto.Request(date.toString(), kg, rest(fields, 1)));
    }

    /** Runs a DTO constructor or check, turning its validation message into a line-numbered failure. */
    private static <T> T validated(int lineNo, Supplier<T> build) {
        try {
            return build.get();
        } catch (ValidationException e) {
            throw invalid(lineNo, e.getMessage());
        }
    }

    /**
     * Comma-separated set groups: {@code 5x100}, {@code 3x5@100}, {@code 5,5,4@70}, and bare reps for
     * bodyweight. Reps are kept as doubles so the DTO, not this parser, decides what a valid rep count is.
     */
    static List<WorkoutDto.SetRequest> parseSets(String text, int lineNo) {
        rejectPounds(text, lineNo);
        List<WorkoutDto.SetRequest> sets = new ArrayList<>();
        List<Double> pendingReps = new ArrayList<>();
        for (String raw : text.split(",", -1)) {
            String token = raw.strip();
            Matcher m;
            if (token.isEmpty()) {
                throw invalid(lineNo, "empty set in \"" + text.strip() + "\"");
            } else if ((m = SETS_OF_REPS_AT.matcher(token)).matches()) {
                addBodyweight(sets, pendingReps);
                double count = Double.parseDouble(m.group(1));
                if (count < 1 || count > WorkoutDto.MAX_SETS) {
                    throw invalid(lineNo, "set count must be between 1 and " + WorkoutDto.MAX_SETS + " in \"" + token + "\"");
                }
                for (int i = 0; i < count; i++) {
                    sets.add(set(m.group(2), m.group(3)));
                }
            } else if ((m = REPS_X_WEIGHT.matcher(token)).matches()) {
                addBodyweight(sets, pendingReps);
                sets.add(set(m.group(1), m.group(2)));
            } else if ((m = REPS_AT.matcher(token)).matches()) {
                pendingReps.add(Double.parseDouble(m.group(1)));
                double weight = Double.parseDouble(m.group(2));
                for (double reps : pendingReps) {
                    sets.add(new WorkoutDto.SetRequest(reps, weight));
                }
                pendingReps.clear();
            } else if ((m = BARE_REPS.matcher(token)).matches()) {
                pendingReps.add(Double.parseDouble(m.group(1)));
            } else {
                throw invalid(lineNo, "can't read the set \"" + token + "\". Try 5x100, 3x5@100, 5,5,4@70 or 8,8,6");
            }
        }
        addBodyweight(sets, pendingReps);
        return sets;
    }

    private static WorkoutDto.SetRequest set(String reps, String weightKg) {
        return new WorkoutDto.SetRequest(Double.parseDouble(reps), Double.parseDouble(weightKg));
    }

    private static void addBodyweight(List<WorkoutDto.SetRequest> sets, List<Double> pendingReps) {
        for (double reps : pendingReps) {
            sets.add(new WorkoutDto.SetRequest(reps, 0.0));
        }
        pendingReps.clear();
    }

    /**
     * All lift lines form one workout (§4). The DTO validates the whole tree and names problems as
     * "(exercise 2, set 3)"; those are rewritten to the message's line numbers.
     */
    private static WorkoutDto.Request buildWorkout(LocalDate date, String notes, int noteLine,
            List<WorkoutDto.ExerciseRequest> exercises, List<Integer> exerciseLines) {
        try {
            return new WorkoutDto.Request(date.toString(), notes, null, exercises);
        } catch (ValidationException e) {
            String message = e.getMessage();
            Matcher ref = EXERCISE_REF.matcher(message);
            if (ref.find()) {
                int line = exerciseLines.get(Integer.parseInt(ref.group(1)) - 1);
                String set = ref.group(2) == null ? "" : " (" + ref.group(2) + ")";
                throw invalid(line, ref.replaceFirst("") + set);
            }
            if (message.startsWith("Workout notes")) {
                throw invalid(noteLine, message);
            }
            throw new InvalidLogException(message);
        }
    }

    private static Double number(String text, Pattern pattern, int lineNo, String label) {
        rejectPounds(text, lineNo);
        Matcher m = pattern.matcher(text);
        if (!m.matches()) {
            throw invalid(lineNo, label + " must be a number, not \"" + text + "\"");
        }
        return Double.parseDouble(m.group(1));
    }

    private static void rejectPounds(String text, int lineNo) {
        if (POUNDS.matcher(text).find()) {
            throw invalid(lineNo, "weights are in kg only; convert lb to kg");
        }
    }

    private static List<String> fields(String value) {
        List<String> fields = new ArrayList<>();
        for (String field : value.split("\\|", -1)) {
            fields.add(field.strip());
        }
        return fields;
    }

    /** Fields from {@code from} on, joined back: notes are the last field and may themselves contain "|". */
    private static String rest(List<String> fields, int from) {
        return from >= fields.size() ? "" : String.join(" | ", fields.subList(from, fields.size()));
    }

    private static InvalidLogException invalid(int lineNo, String reason) {
        return new InvalidLogException("Line " + lineNo + ": " + reason);
    }
}
