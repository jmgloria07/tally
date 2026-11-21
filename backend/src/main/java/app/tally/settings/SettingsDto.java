package app.tally.settings;

import app.tally.common.Checks;
import app.tally.common.Numbers;

/** API shapes for {@code /api/settings}. */
public final class SettingsDto {

    private SettingsDto() {
    }

    /** Response: numbers as JSON numbers (2000, not 2000.0), text as strings. */
    public record Response(
            Number caloriesTarget,
            Number proteinTarget,
            Number carbsTarget,
            Number fatTarget,
            String goal,
            String dietaryPreferences,
            String injuriesLimitations,
            String coachingStyle) {

        public static Response from(Settings s) {
            return new Response(
                    Numbers.jsonNumber(s.caloriesTarget()),
                    Numbers.jsonNumber(s.proteinTarget()),
                    Numbers.jsonNumber(s.carbsTarget()),
                    Numbers.jsonNumber(s.fatTarget()),
                    s.goal(),
                    s.dietaryPreferences(),
                    s.injuriesLimitations(),
                    s.coachingStyle());
        }
    }

    /** {@code PUT} body: replaces all eight settings. Validated on construction (spec §7.3). */
    public record Request(
            Double caloriesTarget,
            Double proteinTarget,
            Double carbsTarget,
            Double fatTarget,
            String goal,
            String dietaryPreferences,
            String injuriesLimitations,
            String coachingStyle) {

        public Request {
            caloriesTarget = Checks.number(caloriesTarget, "Calories target", 500, 10000, null);
            proteinTarget = Checks.number(proteinTarget, "Protein target", 0, 1000, null);
            carbsTarget = Checks.number(carbsTarget, "Carbs target", 0, 1500, null);
            fatTarget = Checks.number(fatTarget, "Fat target", 0, 1000, null);
            goal = Checks.text(goal, "Goal", 2000, false);
            dietaryPreferences = Checks.text(dietaryPreferences, "Dietary preferences", 1000, false);
            injuriesLimitations = Checks.text(injuriesLimitations, "Injuries / limitations", 1000, false);
            coachingStyle = Checks.text(coachingStyle, "Coaching style", 1000, false);
        }

        Settings toSettings() {
            return new Settings(caloriesTarget, proteinTarget, carbsTarget, fatTarget, goal,
                    dietaryPreferences, injuriesLimitations, coachingStyle);
        }
    }
}
