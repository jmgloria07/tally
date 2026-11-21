package app.fitlocal.coach;

import app.fitlocal.common.Numbers;
import app.fitlocal.meal.Meal;
import app.fitlocal.settings.Settings;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The numbers the coach context states so the model never has to count or compute them (decision D5):
 * per-day nutrition totals, the four 7-day blocks of the 28-day window, and per-block summaries. Pure
 * functions with no Spring or database access, so they can be unit-tested directly.
 */
final class CoachStats {

    static final int BLOCK_DAYS = 7;
    static final int BLOCKS = 4;

    private CoachStats() {
    }

    /** One calendar day's nutrition: totals rounded to one decimal, the same values the context prints. */
    record DayNutrition(LocalDate date, int meals, double calories, double protein, double carbs, double fat) {

        boolean logged() {
            return meals > 0;
        }

        /** Within 10% of the target, inclusive: for a 2000 kcal target, 1800 and 2200 both count. */
        boolean caloriesWithinBand(double target) {
            return Math.abs(calories - target) * 10 <= target;
        }

        boolean caloriesUnder(double target) {
            return (target - calories) * 10 > target;
        }

        boolean caloriesOver(double target) {
            return (calories - target) * 10 > target;
        }

        boolean proteinMet(double target) {
            return protein >= target;
        }
    }

    /** A 7-day block of completed days. {@code label} reads like "8–14 days ago". */
    record Block(String label, LocalDate from, LocalDate to) {

        boolean contains(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }

        boolean isLast() {
            return label.startsWith("Last");
        }

        /** "last 7 days" for the newest block, the label otherwise; for inline lists. */
        String shortLabel() {
            return isLast() ? "last " + BLOCK_DAYS + " days" : label;
        }
    }

    /** Summary of a run of days. Averages and target counts cover logged days only. */
    record NutritionSummary(
            int totalDays,
            int loggedDays,
            int mealEntries,
            int minMealsPerDay,
            int maxMealsPerDay,
            double avgCalories,
            double avgProtein,
            int caloriesWithinBandDays,
            List<LocalDate> caloriesUnderDates,
            List<LocalDate> caloriesOverDates,
            int proteinMetDays,
            DayNutrition lowestProtein) {
    }

    static DayNutrition nutrition(LocalDate date, List<Meal> meals) {
        return new DayNutrition(date, meals.size(),
                Numbers.round1(meals.stream().mapToDouble(Meal::getCalories).sum()),
                Numbers.round1(meals.stream().mapToDouble(Meal::getProteinG).sum()),
                Numbers.round1(meals.stream().mapToDouble(Meal::getCarbsG).sum()),
                Numbers.round1(meals.stream().mapToDouble(Meal::getFatG).sum()));
    }

    /** One entry for every date from {@code from} to {@code to} inclusive, oldest first, logged or not. */
    static List<DayNutrition> days(List<Meal> meals, LocalDate from, LocalDate to) {
        Map<LocalDate, List<Meal>> byDay = meals.stream().collect(Collectors.groupingBy(Meal::getMealDate));
        List<DayNutrition> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(nutrition(d, byDay.getOrDefault(d, List.of())));
        }
        return List.copyOf(days);
    }

    /** The four 7-day blocks ending {@code yesterday}, oldest first; the last is "Last 7 completed days". */
    static List<Block> blocks(LocalDate yesterday) {
        List<Block> blocks = new ArrayList<>();
        for (int i = BLOCKS - 1; i >= 0; i--) {
            LocalDate to = yesterday.minusDays((long) BLOCK_DAYS * i);
            LocalDate from = to.minusDays(BLOCK_DAYS - 1);
            String label = i == 0
                    ? "Last " + BLOCK_DAYS + " completed days"
                    : (BLOCK_DAYS * i + 1) + "–" + (BLOCK_DAYS * i + BLOCK_DAYS) + " days ago";
            blocks.add(new Block(label, from, to));
        }
        return List.copyOf(blocks);
    }

    static NutritionSummary summarize(List<DayNutrition> days, Settings settings) {
        List<DayNutrition> logged = days.stream().filter(DayNutrition::logged).toList();
        int count = logged.size();
        double calorieTarget = settings.caloriesTarget();
        return new NutritionSummary(
                days.size(),
                count,
                logged.stream().mapToInt(DayNutrition::meals).sum(),
                logged.stream().mapToInt(DayNutrition::meals).min().orElse(0),
                logged.stream().mapToInt(DayNutrition::meals).max().orElse(0),
                count == 0 ? 0 : logged.stream().mapToDouble(DayNutrition::calories).sum() / count,
                count == 0 ? 0 : logged.stream().mapToDouble(DayNutrition::protein).sum() / count,
                (int) logged.stream().filter(d -> d.caloriesWithinBand(calorieTarget)).count(),
                logged.stream().filter(d -> d.caloriesUnder(calorieTarget)).map(DayNutrition::date).toList(),
                logged.stream().filter(d -> d.caloriesOver(calorieTarget)).map(DayNutrition::date).toList(),
                (int) logged.stream().filter(d -> d.proteinMet(settings.proteinTarget())).count(),
                logged.stream().min(Comparator.comparingDouble(DayNutrition::protein)).orElse(null));
    }
}
