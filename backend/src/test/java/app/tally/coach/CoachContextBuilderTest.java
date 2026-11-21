package app.tally.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.tally.IntegrationTest;
import app.tally.coach.CoachContextBuilder.CoachContext;
import app.tally.workout.WorkoutDto.ExerciseRequest;
import app.tally.workout.WorkoutDto.Request;
import app.tally.workout.WorkoutDto.SetRequest;
import app.tally.workout.WorkoutService;
import java.time.LocalDateTime;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Golden-text tests for what the model reads: the intent's main quality requirement. Fixtures are synthetic.
 * With "now" at 2026-11-20 12:00 the window is 2026-10-23 to 2026-11-19, in blocks starting on Fridays.
 */
class CoachContextBuilderTest extends IntegrationTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 11, 20, 12, 0);

    @Autowired
    CoachContextBuilder builder;

    @Autowired
    WorkoutService workoutService;

    @Test
    void trackerBlockForATypicalMonth() {
        meal("2026-11-20", "Oatmeal with whey", 420, 32, 55, 8, "1 scoop");
        meal("2026-11-20", "Chicken rice", 830, 48, 85, 32, "");
        meal("2026-11-18", "Pasta", 2150, 155, 210, 72.5, "");
        meal("2026-11-16", "Eggs", 500, 40, 5, 35, "");
        meal("2026-11-16", "Steak dinner", 1800, 120, 60, 70, "");
        meal("2026-11-15", "Toast", 600, 20, 80, 15, "");
        meal("2026-11-12", "Earlier in the month", 999, 1, 1, 1, "");
        meal("2026-11-08", "Curry", 1900, 150, 230, 55, "");
        meal("2026-10-23", "Oldest day in the window", 2000, 150, 220, 60, "");
        meal("2026-10-22", "Outside the window", 999, 1, 1, 1, "");
        meal("2026-11-21", "Future-dated", 999, 1, 1, 1, "");
        workout("2026-10-25", "", new ExerciseRequest("Squat", "", List.of(set(5, 57.5))));
        workout("2026-11-16", "", new ExerciseRequest("Squat", "", List.of(set(5, 60), set(5, 62.5))));
        workout("2026-11-18", "Felt strong",
                new ExerciseRequest("Incline DB Press", "Hard sets",
                        List.of(set(8, 17.5), set(8, 17.5), set(8, 17.5), set(7, 17.5))),
                new ExerciseRequest("Hanging Knee Raise", "", List.of(set(12, 0), set(12, 0), set(10, 0))));
        workout("2026-11-20", "", new ExerciseRequest("Push-up", "", List.of(set(15, 0), set(12, 0))));
        weighIn("2026-10-20", 73.4, "");
        weighIn("2026-11-02", 72.9, "");
        weighIn("2026-11-15", 72.4, "BF: 17.5%, MM: 37%");
        weighIn("2026-11-20", 72.1, "");

        CoachContext context = builder.build(NOON);

        assertThat(context.trackerBlock()).isEqualTo("""
                Now: 2026-11-20 (Friday) 12:00 local time.
                Daily targets: 2000 kcal, protein 150 g, carbs 220 g, fat 60 g.

                TRACKER DATA — the 28 completed days from 2026-10-23 (Fri) to 2026-11-19 (Thu), as four 7-day blocks, plus today so far.
                Units: body weight and loads in kg, energy in kcal, macros in grams. Lists are oldest first; every line starts with its own date.

                [LAST 7 COMPLETED DAYS AT A GLANCE — 2026-11-13 (Fri) to 2026-11-19 (Thu), computed by the app]
                Meals: logged on 3 of 7 days; 4 meal entries (1 to 2 per day).
                Calories: average 1683.3 kcal on logged days; within 10% of target on 1 day; more than 10% under on 1 day (2026-11-15); more than 10% over on 1 day (2026-11-16).
                Protein: at or above target on 2 of 3 logged days; lowest 20 g on 2026-11-15.
                Training: 2 sessions in these 7 days (2026-11-16, 2026-11-18); 1 session logged today. Sessions per block, oldest first: 22–28 days ago 1, 15–21 days ago 0, 8–14 days ago 0, last 7 days 2.
                Body weight: 1 weigh-in in these 7 days (72.4 kg on 2026-11-15); today: 72.1 kg. Change: 0.8 kg lower on 2026-11-20 than on 2026-11-02 (3 weigh-ins).

                [TODAY SO FAR — 2026-11-20 (Fri), in progress as of 12:00]
                Meals today: 2 so far — 1250 of 2000 kcal (750 left); protein 80 of 150 g (70 left); carbs 140 of 220 g (80 left); fat 40 of 60 g (20 left)
                2026-11-20 (Fri) Oatmeal with whey — 420 kcal, protein 32 g, carbs 55 g, fat 8 g — note: "1 scoop"
                2026-11-20 (Fri) Chicken rice — 830 kcal, protein 48 g, carbs 85 g, fat 32 g
                Training today: 1 session.
                2026-11-20 (Fri) Push-up — sets: 15 reps, 12 (no added weight)

                [LAST 7 COMPLETED DAYS — daily nutrition vs targets]
                2026-11-13 (Fri): no meals logged
                2026-11-14 (Sat): no meals logged
                2026-11-15 (Sun): 600 of 2000 kcal (1400 under); protein 20 of 150 g (130 under); carbs 80 of 220 g (140 under); fat 15 of 60 g (45 under); 1 meal
                2026-11-16 (Mon): 2300 of 2000 kcal (300 over); protein 160 of 150 g (10 over); carbs 65 of 220 g (155 under); fat 105 of 60 g (45 over); 2 meals
                2026-11-17 (Tue): no meals logged
                2026-11-18 (Wed): 2150 of 2000 kcal (150 over); protein 155 of 150 g (5 over); carbs 210 of 220 g (10 under); fat 72.5 of 60 g (12.5 over); 1 meal
                2026-11-19 (Thu): no meals logged

                [MEALS — last 7 completed days]
                2026-11-15 (Sun) Toast — 600 kcal, protein 20 g, carbs 80 g, fat 15 g
                2026-11-16 (Mon) Eggs — 500 kcal, protein 40 g, carbs 5 g, fat 35 g
                2026-11-16 (Mon) Steak dinner — 1800 kcal, protein 120 g, carbs 60 g, fat 70 g
                2026-11-18 (Wed) Pasta — 2150 kcal, protein 155 g, carbs 210 g, fat 72.5 g

                [EARLIER BLOCKS — nutrition summaries]
                22–28 days ago, 2026-10-23 (Fri) to 2026-10-29 (Thu): meals on 1 of 7 days; average 2000 kcal and protein 150 g on logged days; calories within 10% on 1 day; protein at target on 1 day.
                15–21 days ago, 2026-10-30 (Fri) to 2026-11-05 (Thu): no meals logged.
                8–14 days ago, 2026-11-06 (Fri) to 2026-11-12 (Thu): meals on 2 of 7 days; average 1449.5 kcal and protein 75.5 g on logged days; calories within 10% on 1 day; protein at target on 1 day.
                All 28 days: meals on 6 of 28 days; average 1658.2 kcal and protein 106 g on logged days.

                [WORKOUTS — by 7-day block, oldest first]
                22–28 days ago, 2026-10-23 (Fri) to 2026-10-29 (Thu): 1 session
                2026-10-25 (Sun) Squat — sets: 5 reps @ 57.5 kg
                15–21 days ago, 2026-10-30 (Fri) to 2026-11-05 (Thu): no sessions
                8–14 days ago, 2026-11-06 (Fri) to 2026-11-12 (Thu): no sessions
                Last 7 completed days, 2026-11-13 (Fri) to 2026-11-19 (Thu): 2 sessions
                2026-11-16 (Mon) Squat — sets: 5 reps @ 60 kg, 5 @ 62.5
                2026-11-18 (Wed) session note: "Felt strong"
                2026-11-18 (Wed) Incline DB Press — sets: 8 reps @ 17.5 kg, 8 @ 17.5, 8 @ 17.5, 7 @ 17.5 — note: "Hard sets"
                2026-11-18 (Wed) Hanging Knee Raise — sets: 12 reps, 12, 10 (no added weight)
                (Today's training is listed under TODAY SO FAR.)

                [BODY WEIGHT — oldest first]
                2026-11-02 (Mon) 72.9 kg
                2026-11-15 (Sun) 72.4 kg — note: "BF: 17.5%, MM: 37%"
                2026-11-20 (Fri) 72.1 kg (today)
                Change: 0.8 kg lower on 2026-11-20 than on 2026-11-02 (3 weigh-ins).""");
    }

    /** The original bug report: at 01:28 nothing is logged yet, and today must not look like a missed day. */
    @Test
    void justAfterMidnightTodayIsInProgressNotAGap() {
        meal("2026-11-20", "Late dinner", 2000, 150, 220, 60, "");

        String block = builder.build(LocalDateTime.of(2026, 11, 21, 1, 28)).trackerBlock();

        assertThat(block)
                .startsWith("Now: 2026-11-21 (Saturday) 01:28 local time.\n"
                        + "Daily targets: 2000 kcal, protein 150 g, carbs 220 g, fat 60 g.\n")
                .contains("Meals: logged on 1 of 7 days; 1 meal entry (1 per day).\n")
                .contains("Training: no sessions in these 7 days; none logged today. No sessions on record before today.\n")
                .contains("[TODAY SO FAR — 2026-11-21 (Sat), in progress as of 01:28]\n"
                        + "Nothing logged yet today (the day is in progress).\n")
                .contains("[LAST 7 COMPLETED DAYS — daily nutrition vs targets]\n2026-11-14 (Sat): no meals logged\n")
                .contains("2026-11-20 (Fri): 2000 of 2000 kcal (at target); protein 150 of 150 g (at target); "
                        + "carbs 220 of 220 g (at target); fat 60 of 60 g (at target); 1 meal\n")
                .doesNotContain("2026-11-21 (Sat): no meals logged");
    }

    /** Both test models said "no training this week" while a session was logged today. */
    @Test
    void todaysWorkoutAppearsInTheGlanceAndTodaySections() {
        workout("2026-11-20", "", new ExerciseRequest("Row", "", List.of(set(10, 40))));

        assertThat(builder.build(NOON).trackerBlock())
                .contains("Training: no sessions in these 7 days; 1 session logged today. "
                        + "No sessions on record before today.\n")
                .contains("""
                        [TODAY SO FAR — 2026-11-20 (Fri), in progress as of 12:00]
                        Meals today: none logged yet.
                        Training today: 1 session.
                        2026-11-20 (Fri) Row — sets: 10 reps @ 40 kg
                        """)
                .contains("[WORKOUTS — by 7-day block, oldest first]\nNo workouts logged in the 28 completed days.\n"
                        + "(Today's training is listed under TODAY SO FAR.)\n");
    }

    @Test
    void lastSessionBeforeTheWindowIsNamedWhenThereAreNoneInIt() {
        workout("2026-10-01", "", new ExerciseRequest("Squat", "", List.of(set(5, 50))));

        assertThat(builder.build(NOON).trackerBlock())
                .contains("Training: no sessions in these 7 days; none logged today. "
                        + "No sessions in the 28 completed days; most recent before them: 2026-10-01.\n");
    }

    /** Qwen called the weight "stable, no trend" when the change was stated; it must be up front. */
    @Test
    void weightChangeIsStatedEvenWithoutWeighInsThisWeek() {
        weighIn("2026-11-02", 72.9, "");
        weighIn("2026-11-05", 72.5, "");

        assertThat(builder.build(NOON).trackerBlock())
                .contains("Body weight: no weigh-ins in these 7 days; latest 72.5 kg on 2026-11-05. "
                        + "Change: 0.4 kg lower on 2026-11-05 than on 2026-11-02 (2 weigh-ins).\n");
    }

    @Test
    void emptyMonthSaysNothingWasLoggedInEverySection() {
        weighIn("2026-10-02", 72.9, "");

        String block = builder.build(NOON).trackerBlock();

        assertThat(block)
                .contains("Meals: none logged in these 7 days.\nCalories: no data (no meals logged).\n"
                        + "Protein: no data (no meals logged).\n")
                .contains("Training: no sessions in these 7 days; none logged today. No sessions on record before today.\n")
                .contains("Body weight: no weigh-ins in these 7 days; latest 72.9 kg on 2026-10-02.\n")
                .contains("Nothing logged yet today (the day is in progress).\n")
                .contains("2026-11-19 (Thu): no meals logged\n\n[MEALS — last 7 completed days]\n"
                        + "No meals logged in the last 7 completed days.\n")
                .contains("15–21 days ago, 2026-10-30 (Fri) to 2026-11-05 (Thu): no meals logged.\n")
                .contains("All 28 days: no meals logged.\n")
                .contains("[WORKOUTS — by 7-day block, oldest first]\nNo workouts logged in the 28 completed days.\n")
                .endsWith("[BODY WEIGHT — oldest first]\nNo weigh-ins in this window.\n"
                        + "Most recent before this window: 2026-10-02 (Fri) 72.9 kg.");
    }

    @Test
    void mealsOnlyInAnEarlierBlockAreSummarizedButNotItemized() {
        meal("2026-11-01", "Early meal", 1800, 120, 200, 50, "");

        assertThat(builder.build(NOON).trackerBlock())
                .contains("Meals: none logged in these 7 days.\n")
                .contains("15–21 days ago, 2026-10-30 (Fri) to 2026-11-05 (Thu): meals on 1 of 7 days; "
                        + "average 1800 kcal and protein 120 g on logged days; calories within 10% on 1 day; "
                        + "protein at target on 0 days.\n")
                .contains("All 28 days: meals on 1 of 28 days; average 1800 kcal and protein 120 g on logged days.\n")
                .doesNotContain("Early meal");
    }

    @Test
    void singleWeighInHasNoTrend() {
        weighIn("2026-11-19", 72.0, "");

        assertThat(builder.build(NOON).trackerBlock())
                .contains("Body weight: 1 weigh-in in these 7 days (72 kg on 2026-11-19). "
                        + "Change: no trend can be computed (fewer than two weigh-ins in the window).\n")
                .endsWith("""
                        [BODY WEIGHT — oldest first]
                        2026-11-19 (Thu) 72 kg
                        Only one weigh-in; no trend can be computed.""");
    }

    @Test
    void systemPromptHasRulesThenLabeledProfile() {
        jdbc.update("INSERT INTO settings (key, value) VALUES ('injuries_limitations', 'Left knee\nNo deep lunges')");

        String prompt = builder.build(NOON).systemPrompt();

        assertThat(prompt).startsWith("You are Tally, a practical fitness and habit coach.");
        assertThat(prompt)
                .contains("Start from the AT A GLANCE section.")
                .contains("never work out a date from a line's position")
                .contains("Training logged today counts as this week's training.")
                .contains("TODAY SO FAR is in progress")
                .contains("A completed day with no meals logged is missing data, not fasting or zero intake.");
        assertThat(prompt.indexOf("READING THE TRACKER DATA")).isLessThan(prompt.indexOf("OUTPUT FORMATTING"));
        assertThat(prompt).endsWith("""
                USER PROFILE (set by the user in Goals & Preferences)
                Goal: Build strength and improve body composition
                Dietary preferences / restrictions: (not provided)
                Injuries / limitations:
                  Left knee
                  No deep lunges
                Coaching style: Practical, direct, encouraging; prioritize consistency over perfection.
                Daily targets: 2000 kcal, protein 150 g, carbs 220 g, fat 60 g""");
    }

    @Test
    void contextEndpointShowsWhatTheModelWouldReceive() throws Exception {
        mvc.perform(get("/api/coach/context")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/coach/context").cookie(signIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackerBlock").value(Matchers.startsWith("Now: 2026-11-20 (Friday) 12:00")))
                .andExpect(jsonPath("$.systemPrompt").value(Matchers.startsWith("You are Tally")));
    }

    private void meal(String date, String food, double calories, double protein, double carbs, double fat,
            String notes) {
        jdbc.update("INSERT INTO meals (meal_date, food, calories, protein_g, carbs_g, fat_g, notes, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", date, food, calories, protein, carbs, fat, notes, date + "T12:00:00");
    }

    private void workout(String date, String notes, ExerciseRequest... exercises) {
        workoutService.create(new Request(date, notes, null, List.of(exercises)));
    }

    private void weighIn(String date, double weight, String notes) {
        jdbc.update("INSERT INTO weigh_ins (weigh_date, weight_kg, notes, created_at) VALUES (?, ?, ?, ?)",
                date, weight, notes, date + "T07:00:00");
    }

    private static SetRequest set(double reps, double weight) {
        return new SetRequest(reps, weight);
    }
}
