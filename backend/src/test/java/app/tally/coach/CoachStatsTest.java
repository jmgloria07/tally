package app.tally.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.tally.coach.CoachStats.Block;
import app.tally.coach.CoachStats.DayNutrition;
import app.tally.coach.CoachStats.NutritionSummary;
import app.tally.meal.Meal;
import app.tally.settings.Settings;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoachStatsTest {

    private static final Settings TARGETS = new Settings(2000, 150, 220, 60, "", "", "", "");

    @Test
    void calorieBandIsInclusiveAtExactlyTenPercent() {
        assertThat(day("2026-11-01", 1, 1800, 0).caloriesWithinBand(2000)).isTrue();
        assertThat(day("2026-11-01", 1, 1800, 0).caloriesUnder(2000)).isFalse();
        assertThat(day("2026-11-01", 1, 2200, 0).caloriesWithinBand(2000)).isTrue();
        assertThat(day("2026-11-01", 1, 2200, 0).caloriesOver(2000)).isFalse();

        assertThat(day("2026-11-01", 1, 1799.9, 0).caloriesUnder(2000)).isTrue();
        assertThat(day("2026-11-01", 1, 1799.9, 0).caloriesWithinBand(2000)).isFalse();
        assertThat(day("2026-11-01", 1, 2200.1, 0).caloriesOver(2000)).isTrue();
        assertThat(day("2026-11-01", 1, 2200.1, 0).caloriesWithinBand(2000)).isFalse();
    }

    @Test
    void proteinTargetIsMetAtEquality() {
        assertThat(day("2026-11-01", 1, 0, 150).proteinMet(150)).isTrue();
        assertThat(day("2026-11-01", 1, 0, 149.9).proteinMet(150)).isFalse();
    }

    @Test
    void daysListsEveryDateOldestFirstWithRoundedTotals() {
        List<Meal> meals = List.of(
                meal("2026-11-04", 500, 30),
                meal("2026-11-02", 100.04, 10),
                meal("2026-11-02", 200.04, 20));

        List<DayNutrition> days = CoachStats.days(meals, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 4));

        assertThat(days).extracting(DayNutrition::date).containsExactly(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4));
        assertThat(days).extracting(DayNutrition::logged).containsExactly(false, true, false, true);
        assertThat(days.get(1).meals()).isEqualTo(2);
        assertThat(days.get(1).calories()).isEqualTo(300.1);
        assertThat(days.get(1).protein()).isEqualTo(30);
    }

    @Test
    void summaryAveragesAndCountsCoverLoggedDaysOnly() {
        List<DayNutrition> days = List.of(
                day("2026-11-01", 0, 0, 0),
                day("2026-11-02", 1, 600, 20),
                day("2026-11-03", 2, 2300, 160),
                day("2026-11-04", 1, 2150, 155));

        NutritionSummary s = CoachStats.summarize(days, TARGETS);

        assertThat(s.totalDays()).isEqualTo(4);
        assertThat(s.loggedDays()).isEqualTo(3);
        assertThat(s.mealEntries()).isEqualTo(4);
        assertThat(s.minMealsPerDay()).isEqualTo(1);
        assertThat(s.maxMealsPerDay()).isEqualTo(2);
        assertThat(s.avgCalories()).isCloseTo(5050.0 / 3, within(1e-9));
        assertThat(s.avgProtein()).isCloseTo(335.0 / 3, within(1e-9));
        assertThat(s.caloriesWithinBandDays()).isEqualTo(1);
        assertThat(s.caloriesUnderDates()).containsExactly(LocalDate.of(2026, 11, 2));
        assertThat(s.caloriesOverDates()).containsExactly(LocalDate.of(2026, 11, 3));
        assertThat(s.proteinMetDays()).isEqualTo(2);
        assertThat(s.lowestProtein().date()).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void summaryOfAnEmptyWeekHasNoAveragesOrLowestDay() {
        NutritionSummary s = CoachStats.summarize(
                CoachStats.days(List.of(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7)), TARGETS);

        assertThat(s.totalDays()).isEqualTo(7);
        assertThat(s.loggedDays()).isZero();
        assertThat(s.avgCalories()).isZero();
        assertThat(s.lowestProtein()).isNull();
        assertThat(s.caloriesUnderDates()).isEmpty();
    }

    @Test
    void blocksAreFourSevenDayRunsEndingYesterdayOldestFirst() {
        List<Block> blocks = CoachStats.blocks(LocalDate.of(2026, 11, 19));

        assertThat(blocks).extracting(Block::label).containsExactly(
                "22–28 days ago", "15–21 days ago", "8–14 days ago", "Last 7 completed days");
        assertThat(blocks.getFirst().from()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(blocks.getFirst().to()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(blocks.getLast().from()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(blocks.getLast().to()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(blocks.getLast().shortLabel()).isEqualTo("last 7 days");
        assertThat(blocks.get(1).shortLabel()).isEqualTo("15–21 days ago");
        assertThat(blocks.getLast().contains(LocalDate.of(2026, 11, 13))).isTrue();
        assertThat(blocks.getLast().contains(LocalDate.of(2026, 11, 12))).isFalse();
        assertThat(blocks.getLast().contains(LocalDate.of(2026, 11, 20))).isFalse();
    }

    private static DayNutrition day(String date, int meals, double calories, double protein) {
        return new DayNutrition(LocalDate.parse(date), meals, calories, protein, 0, 0);
    }

    private static Meal meal(String date, double calories, double protein) {
        Meal meal = new Meal(LocalDateTime.of(2026, 11, 1, 12, 0));
        meal.update(LocalDate.parse(date), "Food", calories, protein, 0, 0, "");
        return meal;
    }
}
