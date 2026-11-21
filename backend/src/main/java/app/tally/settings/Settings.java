package app.tally.settings;

/** The user's goals and preferences after stored rows are merged over the in-code defaults. */
public record Settings(
        double caloriesTarget,
        double proteinTarget,
        double carbsTarget,
        double fatTarget,
        String goal,
        String dietaryPreferences,
        String injuriesLimitations,
        String coachingStyle) {
}
