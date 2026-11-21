package app.tally.coach;

import static app.tally.common.Numbers.oneDecimal;

import app.tally.coach.CoachStats.Block;
import app.tally.coach.CoachStats.DayNutrition;
import app.tally.coach.CoachStats.NutritionSummary;
import app.tally.common.Numbers;
import app.tally.meal.Meal;
import app.tally.meal.MealRepository;
import app.tally.settings.Settings;
import app.tally.settings.SettingsService;
import app.tally.weighin.WeighIn;
import app.tally.weighin.WeighInRepository;
import app.tally.workout.Workout;
import app.tally.workout.WorkoutExercise;
import app.tally.workout.WorkoutRepository;
import app.tally.workout.WorkoutSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds what the model reads: a system prompt (rules + user profile) and a tracker block, as compact
 * labeled plain text (spec §8.4). The app does all arithmetic; the model is told to quote the computed values.
 * Runs in a short read-only transaction that ends before the LM Studio call.
 *
 * <p>Layout (decision D5), shaped by how real models misread the earlier version:
 * <ol>
 *   <li>An <b>at a glance</b> section for the last 7 completed days with every count, average and change a
 *       review needs, already computed (see {@link CoachStats}).</li>
 *   <li><b>Today so far</b>, stamped with the time and kept out of all counts (D4).</li>
 *   <li>The last 7 completed days in detail; the three earlier 7-day blocks as one summary line each, so
 *       there is no long list of daily lines to misread.</li>
 *   <li>Workouts grouped by block, then body weight.</li>
 * </ol>
 * Every list is oldest first and every line starts with its own date.
 */
@Component
public class CoachContextBuilder {

    public record CoachContext(String systemPrompt, String trackerBlock) {
    }

    static final int WINDOW_DAYS = CoachStats.BLOCKS * CoachStats.BLOCK_DAYS;
    static final int MAX_MEALS = 120;
    static final int MAX_EXERCISES = 150;
    static final int MAX_WEIGH_INS = 40;
    static final String OMITTED = "(older entries in this window omitted)";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final SettingsService settingsService;
    private final MealRepository mealRepository;
    private final WorkoutRepository workoutRepository;
    private final WeighInRepository weighInRepository;

    public CoachContextBuilder(SettingsService settingsService, MealRepository mealRepository,
            WorkoutRepository workoutRepository, WeighInRepository weighInRepository) {
        this.settingsService = settingsService;
        this.mealRepository = mealRepository;
        this.workoutRepository = workoutRepository;
        this.weighInRepository = weighInRepository;
    }

    /** Everything the sections need, loaded once and sorted oldest first. */
    private record Data(
            LocalDate today,
            String time,
            Settings settings,
            List<Block> blocks,
            List<DayNutrition> days,
            List<Meal> pastMeals,
            List<Meal> todayMeals,
            List<Workout> pastWorkouts,
            List<Workout> todayWorkouts,
            List<WeighIn> weighIns,
            boolean weighInsOmitted,
            Optional<WeighIn> weighInBeforeWindow,
            Optional<Workout> workoutBeforeWindow) {

        Block lastWeek() {
            return blocks.getLast();
        }

        LocalDate from() {
            return blocks.getFirst().from();
        }
    }

    @Transactional(readOnly = true)
    public CoachContext build(LocalDateTime now) {
        Settings settings = settingsService.current();
        LocalDate today = now.toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        List<Block> blocks = CoachStats.blocks(yesterday);
        LocalDate from = blocks.getFirst().from();

        // Repositories return newest first; everything shown to the model is oldest first.
        List<Meal> meals = mealRepository.findByMealDateBetweenOrderByMealDateDescIdDesc(from, today).reversed();
        List<Workout> workouts = workoutRepository
                .findByWorkoutDateBetweenOrderByWorkoutDateDescIdDesc(from, today).reversed();
        List<WeighIn> weighInsNewestFirst = weighInRepository.findByWeighDateBetweenOrderByWeighDateDescIdDesc(from, today);
        List<WeighIn> weighIns = weighInsNewestFirst.subList(0, Math.min(MAX_WEIGH_INS, weighInsNewestFirst.size()))
                .reversed();

        List<Meal> pastMeals = filter(meals, m -> m.getMealDate().isBefore(today));
        List<Workout> pastWorkouts = filter(workouts, w -> w.getWorkoutDate().isBefore(today));
        Data data = new Data(
                today,
                now.format(CLOCK),
                settings,
                blocks,
                CoachStats.days(pastMeals, from, yesterday),
                pastMeals,
                filter(meals, m -> m.getMealDate().equals(today)),
                pastWorkouts,
                filter(workouts, w -> w.getWorkoutDate().equals(today)),
                weighIns,
                weighInsNewestFirst.size() > MAX_WEIGH_INS,
                weighIns.isEmpty()
                        ? weighInRepository.findFirstByWeighDateBeforeOrderByWeighDateDescIdDesc(from)
                        : Optional.empty(),
                pastWorkouts.isEmpty()
                        ? workoutRepository.findFirstByWorkoutDateBeforeOrderByWorkoutDateDescIdDesc(from)
                        : Optional.empty());

        StringBuilder out = new StringBuilder();
        appendHeader(out, data, yesterday);
        out.append('\n');
        appendGlance(out, data);
        out.append('\n');
        appendToday(out, data);
        out.append('\n');
        appendLastWeekDays(out, data);
        out.append('\n');
        appendLastWeekMeals(out, data);
        out.append('\n');
        appendEarlierBlocks(out, data);
        out.append('\n');
        appendWorkouts(out, data);
        out.append('\n');
        appendBodyWeight(out, data);
        return new CoachContext(systemPrompt(settings), out.toString().stripTrailing());
    }

    static String systemPrompt(Settings s) {
        String profile = "USER PROFILE (set by the user in Goals & Preferences)\n"
                + profileField("Goal", s.goal())
                + profileField("Dietary preferences / restrictions", s.dietaryPreferences())
                + profileField("Injuries / limitations", s.injuriesLimitations())
                + profileField("Coaching style", s.coachingStyle())
                + "Daily targets: " + targets(s);
        return CoachPrompts.COACH_RULES + "\n\n" + CoachPrompts.DATA_READING_RULES + "\n\n"
                + CoachPrompts.FORMATTING_RULES + "\n\n" + profile;
    }

    private static String targets(Settings s) {
        return oneDecimal(s.caloriesTarget()) + " kcal, protein " + oneDecimal(s.proteinTarget()) + " g, carbs "
                + oneDecimal(s.carbsTarget()) + " g, fat " + oneDecimal(s.fatTarget()) + " g";
    }

    /** Multi-line values are indented two spaces so a line inside them cannot look like a new label. */
    private static String profileField(String label, String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            return label + ": (not provided)\n";
        }
        if (!text.contains("\n")) {
            return label + ": " + text + "\n";
        }
        StringBuilder out = new StringBuilder(label).append(":\n");
        for (String line : text.split("\\R")) {
            out.append("  ").append(line.stripTrailing()).append('\n');
        }
        return out.toString();
    }

    private static void appendHeader(StringBuilder out, Data d, LocalDate yesterday) {
        out.append("Now: ").append(d.today()).append(" (")
                .append(d.today().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(") ")
                .append(d.time()).append(" local time.\n");
        out.append("Daily targets: ").append(targets(d.settings())).append(".\n\n");
        out.append("TRACKER DATA — the ").append(WINDOW_DAYS).append(" completed days from ").append(day(d.from()))
                // "four" spelled out: "4 7-day blocks" puts two numbers side by side, which invites misreading.
                .append(" to ").append(day(yesterday)).append(", as four 7-day blocks, plus today so far.\n");
        out.append("Units: body weight and loads in kg, energy in kcal, macros in grams. ")
                .append("Lists are oldest first; every line starts with its own date.\n");
    }

    // ---------------------------------------------------------------------------------------------
    // At a glance: the computed facts a weekly review needs, stated once, up front.
    // ---------------------------------------------------------------------------------------------

    private static void appendGlance(StringBuilder out, Data d) {
        Block week = d.lastWeek();
        out.append("[LAST ").append(CoachStats.BLOCK_DAYS).append(" COMPLETED DAYS AT A GLANCE — ")
                .append(day(week.from())).append(" to ").append(day(week.to())).append(", computed by the app]\n");
        NutritionSummary s = CoachStats.summarize(daysIn(d, week), d.settings());
        if (s.loggedDays() == 0) {
            out.append("Meals: none logged in these ").append(CoachStats.BLOCK_DAYS).append(" days.\n");
            out.append("Calories: no data (no meals logged).\n");
            out.append("Protein: no data (no meals logged).\n");
        } else {
            String perDay = s.minMealsPerDay() == s.maxMealsPerDay()
                    ? s.minMealsPerDay() + " per day"
                    : s.minMealsPerDay() + " to " + s.maxMealsPerDay() + " per day";
            out.append("Meals: logged on ").append(s.loggedDays()).append(" of ").append(s.totalDays()).append(" days; ")
                    .append(count(s.mealEntries(), "meal entry", "meal entries")).append(" (").append(perDay).append(").\n");
            out.append("Calories: average ").append(oneDecimal(s.avgCalories())).append(" kcal on logged days; ")
                    .append("within 10% of target on ").append(count(s.caloriesWithinBandDays(), "day")).append("; ")
                    .append("more than 10% under on ").append(daysWithDates(s.caloriesUnderDates())).append("; ")
                    .append("more than 10% over on ").append(daysWithDates(s.caloriesOverDates())).append(".\n");
            out.append("Protein: at or above target on ").append(s.proteinMetDays()).append(" of ")
                    .append(count(s.loggedDays(), "logged day")).append("; lowest ")
                    .append(oneDecimal(s.lowestProtein().protein())).append(" g on ")
                    .append(s.lowestProtein().date()).append(".\n");
        }
        out.append(trainingGlance(d)).append('\n');
        out.append(weightGlance(d)).append('\n');
    }

    private static String trainingGlance(Data d) {
        Block week = d.lastWeek();
        List<Workout> weekSessions = filter(d.pastWorkouts(), w -> week.contains(w.getWorkoutDate()));
        StringBuilder line = new StringBuilder("Training: ");
        if (weekSessions.isEmpty()) {
            line.append("no sessions in these ").append(CoachStats.BLOCK_DAYS).append(" days");
        } else {
            line.append(count(weekSessions.size(), "session")).append(" in these ").append(CoachStats.BLOCK_DAYS)
                    .append(" days (").append(weekSessions.stream().map(w -> w.getWorkoutDate().toString())
                            .distinct().collect(Collectors.joining(", "))).append(')');
        }
        line.append("; ").append(d.todayWorkouts().isEmpty()
                ? "none logged today"
                : count(d.todayWorkouts().size(), "session") + " logged today").append(". ");
        if (d.pastWorkouts().isEmpty()) {
            line.append(d.workoutBeforeWindow()
                    .map(w -> "No sessions in the " + WINDOW_DAYS + " completed days; most recent before them: "
                            + w.getWorkoutDate())
                    .orElse("No sessions on record before today")).append('.');
        } else {
            line.append("Sessions per block, oldest first: ").append(d.blocks().stream()
                    .map(b -> b.shortLabel() + " " + d.pastWorkouts().stream()
                            .filter(w -> b.contains(w.getWorkoutDate())).count())
                    .collect(Collectors.joining(", "))).append('.');
        }
        return line.toString();
    }

    private static String weightGlance(Data d) {
        Block week = d.lastWeek();
        List<WeighIn> weekWeighIns = filter(d.weighIns(), w -> week.contains(w.getWeighDate()));
        Optional<WeighIn> todayWeighIn = d.weighIns().stream()
                .filter(w -> w.getWeighDate().equals(d.today())).reduce((first, second) -> second);
        StringBuilder line = new StringBuilder("Body weight: ");
        if (weekWeighIns.isEmpty()) {
            line.append("no weigh-ins in these ").append(CoachStats.BLOCK_DAYS).append(" days");
        } else {
            line.append(count(weekWeighIns.size(), "weigh-in")).append(" in these ").append(CoachStats.BLOCK_DAYS)
                    .append(" days (").append(weekWeighIns.stream()
                            .map(w -> oneDecimal(w.getWeightKg()) + " kg on " + w.getWeighDate())
                            .collect(Collectors.joining(", "))).append(')');
        }
        if (todayWeighIn.isPresent()) {
            line.append("; today: ").append(oneDecimal(todayWeighIn.get().getWeightKg())).append(" kg");
        } else if (weekWeighIns.isEmpty()) {
            Optional<WeighIn> latest = d.weighIns().isEmpty()
                    ? d.weighInBeforeWindow()
                    : Optional.of(d.weighIns().getLast());
            line.append(latest.map(w -> "; latest " + oneDecimal(w.getWeightKg()) + " kg on " + w.getWeighDate())
                    .orElse("; no weigh-ins on record"));
        }
        line.append('.');
        if (d.weighIns().size() >= 2) {
            line.append(" Change: ").append(weightChange(d.weighIns())).append('.');
        } else if (d.weighIns().size() == 1) {
            line.append(" Change: no trend can be computed (fewer than two weigh-ins in the window).");
        }
        return line.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Today, the last 7 days in detail, and the earlier blocks as summaries.
    // ---------------------------------------------------------------------------------------------

    /** Today is in progress: totals are shown with what is left, never as a gap or a shortfall. */
    private static void appendToday(StringBuilder out, Data d) {
        out.append("[TODAY SO FAR — ").append(day(d.today())).append(", in progress as of ").append(d.time())
                .append("]\n");
        if (d.todayMeals().isEmpty() && d.todayWorkouts().isEmpty()) {
            out.append("Nothing logged yet today (the day is in progress).\n");
            return;
        }
        if (d.todayMeals().isEmpty()) {
            out.append("Meals today: none logged yet.\n");
        } else {
            DayNutrition t = CoachStats.nutrition(d.today(), d.todayMeals());
            out.append("Meals today: ").append(t.meals()).append(" so far — ")
                    .append(versusTargets(t, d.settings(), CoachContextBuilder::remaining)).append('\n');
            d.todayMeals().forEach(m -> appendMealLine(out, m));
        }
        if (d.todayWorkouts().isEmpty()) {
            out.append("Training today: none logged yet.\n");
        } else {
            out.append("Training today: ").append(count(d.todayWorkouts().size(), "session")).append(".\n");
            d.todayWorkouts().forEach(w -> appendSession(out, w));
        }
    }

    private static void appendLastWeekDays(StringBuilder out, Data d) {
        out.append("[LAST ").append(CoachStats.BLOCK_DAYS).append(" COMPLETED DAYS — daily nutrition vs targets]\n");
        for (DayNutrition n : daysIn(d, d.lastWeek())) {
            out.append(day(n.date())).append(": ");
            if (n.logged()) {
                out.append(versusTargets(n, d.settings(), CoachContextBuilder::difference)).append("; ")
                        .append(count(n.meals(), "meal"));
            } else {
                out.append("no meals logged");
            }
            out.append('\n');
        }
    }

    private static void appendLastWeekMeals(StringBuilder out, Data d) {
        out.append("[MEALS — last ").append(CoachStats.BLOCK_DAYS).append(" completed days]\n");
        List<Meal> meals = filter(d.pastMeals(), m -> d.lastWeek().contains(m.getMealDate()));
        if (meals.isEmpty()) {
            out.append("No meals logged in the last ").append(CoachStats.BLOCK_DAYS).append(" completed days.\n");
            return;
        }
        if (meals.size() > MAX_MEALS) {
            out.append(OMITTED).append('\n');
            meals = meals.subList(meals.size() - MAX_MEALS, meals.size());
        }
        meals.forEach(m -> appendMealLine(out, m));
    }

    private static void appendEarlierBlocks(StringBuilder out, Data d) {
        out.append("[EARLIER BLOCKS — nutrition summaries]\n");
        for (Block b : d.blocks()) {
            if (b.isLast()) {
                continue;
            }
            NutritionSummary s = CoachStats.summarize(daysIn(d, b), d.settings());
            out.append(b.label()).append(", ").append(day(b.from())).append(" to ").append(day(b.to())).append(": ");
            if (s.loggedDays() == 0) {
                out.append("no meals logged.\n");
            } else {
                out.append(averages(s)).append("; calories within 10% on ").append(count(s.caloriesWithinBandDays(), "day"))
                        .append("; protein at target on ").append(count(s.proteinMetDays(), "day")).append(".\n");
            }
        }
        NutritionSummary all = CoachStats.summarize(d.days(), d.settings());
        out.append("All ").append(WINDOW_DAYS).append(" days: ")
                .append(all.loggedDays() == 0 ? "no meals logged." : averages(all) + ".").append('\n');
    }

    private static String averages(NutritionSummary s) {
        return "meals on " + s.loggedDays() + " of " + s.totalDays() + " days; average " + oneDecimal(s.avgCalories())
                + " kcal and protein " + oneDecimal(s.avgProtein()) + " g on logged days";
    }

    /** "1250 of 2000 kcal (750 under); protein 80 of 150 g (70 under); carbs …; fat …". */
    private static String versusTargets(DayNutrition n, Settings s, BiFunction<Double, Double, String> compare) {
        return oneDecimal(n.calories()) + " of " + oneDecimal(s.caloriesTarget()) + " kcal ("
                + compare.apply(n.calories(), s.caloriesTarget()) + "); protein "
                + oneDecimal(n.protein()) + " of " + oneDecimal(s.proteinTarget()) + " g ("
                + compare.apply(n.protein(), s.proteinTarget()) + "); carbs "
                + oneDecimal(n.carbs()) + " of " + oneDecimal(s.carbsTarget()) + " g ("
                + compare.apply(n.carbs(), s.carbsTarget()) + "); fat "
                + oneDecimal(n.fat()) + " of " + oneDecimal(s.fatTarget()) + " g ("
                + compare.apply(n.fat(), s.fatTarget()) + ")";
    }

    /** For a completed day: "750 under", "150 over" or "at target". */
    private static String difference(double total, double target) {
        double diff = Numbers.round1(total - target);
        if (diff == 0) {
            return "at target";
        }
        return oneDecimal(Math.abs(diff)) + (diff < 0 ? " under" : " over");
    }

    /** For today, which is still in progress: "750 left", "150 over" or "target reached". */
    private static String remaining(double total, double target) {
        double diff = Numbers.round1(total - target);
        if (diff == 0) {
            return "target reached";
        }
        return oneDecimal(Math.abs(diff)) + (diff < 0 ? " left" : " over");
    }

    private static void appendMealLine(StringBuilder out, Meal m) {
        out.append(day(m.getMealDate())).append(' ').append(oneLine(m.getFood())).append(" — ")
                .append(oneDecimal(m.getCalories())).append(" kcal, protein ").append(oneDecimal(m.getProteinG()))
                .append(" g, carbs ").append(oneDecimal(m.getCarbsG())).append(" g, fat ")
                .append(oneDecimal(m.getFatG())).append(" g").append(note(m.getNotes())).append('\n');
    }

    // ---------------------------------------------------------------------------------------------
    // Workouts by block, and body weight.
    // ---------------------------------------------------------------------------------------------

    private static void appendWorkouts(StringBuilder out, Data d) {
        out.append("[WORKOUTS — by ").append(CoachStats.BLOCK_DAYS).append("-day block, oldest first]\n");
        if (d.pastWorkouts().isEmpty()) {
            out.append("No workouts logged in the ").append(WINDOW_DAYS).append(" completed days.\n");
        } else {
            List<Workout> shown = newestWithinCap(d.pastWorkouts());
            if (shown.size() < d.pastWorkouts().size()) {
                out.append(OMITTED).append('\n');
            }
            for (Block b : d.blocks()) {
                long sessions = d.pastWorkouts().stream().filter(w -> b.contains(w.getWorkoutDate())).count();
                out.append(b.label()).append(", ").append(day(b.from())).append(" to ").append(day(b.to())).append(": ")
                        .append(sessions == 0 ? "no sessions" : count((int) sessions, "session")).append('\n');
                shown.stream().filter(w -> b.contains(w.getWorkoutDate())).forEach(w -> appendSession(out, w));
            }
        }
        if (!d.todayWorkouts().isEmpty()) {
            out.append("(Today's training is listed under TODAY SO FAR.)\n");
        }
    }

    /** The newest sessions whose exercises fit within {@link #MAX_EXERCISES}, oldest first. */
    private static List<Workout> newestWithinCap(List<Workout> oldestFirst) {
        List<Workout> kept = new ArrayList<>();
        int exercises = 0;
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            exercises += oldestFirst.get(i).getExercises().size();
            if (exercises > MAX_EXERCISES) {
                break;
            }
            kept.add(oldestFirst.get(i));
        }
        return kept.reversed();
    }

    private static void appendSession(StringBuilder out, Workout w) {
        if (!w.getNotes().isBlank()) {
            out.append(day(w.getWorkoutDate())).append(" session note: ").append(quote(w.getNotes())).append('\n');
        }
        for (WorkoutExercise e : w.getExercises()) {
            out.append(day(w.getWorkoutDate())).append(' ').append(oneLine(e.getName())).append(" — sets: ")
                    .append(sets(e.getSets())).append(note(e.getNotes())).append('\n');
        }
    }

    /**
     * {@code 8 reps @ 17.5 kg, 8 @ 17.5, 7 @ 17.5}; when every set has weight 0:
     * {@code 12 reps, 12, 10 (no added weight)}.
     */
    static String sets(List<WorkoutSet> sets) {
        boolean bodyweight = sets.stream().allMatch(s -> s.getWeightKg() == 0);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            WorkoutSet s = sets.get(i);
            boolean first = i == 0;
            String part = s.getReps() + (first ? " reps" : "");
            if (!bodyweight) {
                part += " @ " + oneDecimal(s.getWeightKg()) + (first ? " kg" : "");
            }
            parts.add(part);
        }
        return String.join(", ", parts) + (bodyweight ? " (no added weight)" : "");
    }

    /**
     * Weigh-ins are point measurements, so today's counts like any other: it is listed (marked "today") and
     * included in the change line.
     */
    private static void appendBodyWeight(StringBuilder out, Data d) {
        out.append("[BODY WEIGHT — oldest first]\n");
        if (d.weighIns().isEmpty()) {
            out.append("No weigh-ins in this window.\n");
            d.weighInBeforeWindow().ifPresent(w -> out.append("Most recent before this window: ")
                    .append(day(w.getWeighDate())).append(' ').append(oneDecimal(w.getWeightKg())).append(" kg.\n"));
            return;
        }
        if (d.weighInsOmitted()) {
            out.append(OMITTED).append('\n');
        }
        for (WeighIn w : d.weighIns()) {
            out.append(day(w.getWeighDate())).append(' ').append(oneDecimal(w.getWeightKg())).append(" kg")
                    .append(w.getWeighDate().equals(d.today()) ? " (today)" : "")
                    .append(note(w.getNotes())).append('\n');
        }
        if (d.weighIns().size() == 1) {
            out.append("Only one weigh-in; no trend can be computed.\n");
        } else {
            out.append("Change: ").append(weightChange(d.weighIns())).append(".\n");
        }
    }

    /** "0.8 kg lower on 2026-11-20 than on 2026-11-02 (3 weigh-ins)" over an oldest-first list of 2 or more. */
    private static String weightChange(List<WeighIn> oldestFirst) {
        WeighIn first = oldestFirst.getFirst();
        WeighIn last = oldestFirst.getLast();
        double diff = Numbers.round1(last.getWeightKg() - first.getWeightKg());
        String change = diff == 0
                ? "no change on " + last.getWeighDate() + " compared with " + first.getWeighDate()
                : oneDecimal(Math.abs(diff)) + " kg " + (diff < 0 ? "lower" : "higher") + " on " + last.getWeighDate()
                        + " than on " + first.getWeighDate();
        return change + " (" + oldestFirst.size() + " weigh-ins)";
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers.
    // ---------------------------------------------------------------------------------------------

    private static List<DayNutrition> daysIn(Data d, Block b) {
        return filter(d.days(), n -> b.contains(n.date()));
    }

    private static <T> List<T> filter(List<T> items, Predicate<T> keep) {
        return items.stream().filter(keep).toList();
    }

    private static String count(int n, String singular) {
        return count(n, singular, singular + "s");
    }

    private static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** "0 days", or "2 days (2026-11-15, 2026-11-17)". */
    private static String daysWithDates(List<LocalDate> dates) {
        return dates.isEmpty()
                ? count(0, "day")
                : count(dates.size(), "day") + " (" + dates.stream().map(LocalDate::toString)
                        .collect(Collectors.joining(", ")) + ")";
    }

    /** {@code 2026-11-20 (Fri)}. */
    static String day(LocalDate date) {
        return date + " (" + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + ")";
    }

    private static String note(String notes) {
        return notes == null || notes.isBlank() ? "" : " — note: " + quote(notes);
    }

    private static String quote(String text) {
        return "\"" + oneLine(text) + "\"";
    }

    /** Keeps each entry on one line so it cannot be mistaken for a new entry or section. */
    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s*\\R\\s*", " / ");
    }
}
