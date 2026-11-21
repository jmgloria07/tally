package app.fitlocal.meal;

import app.fitlocal.common.Checks;
import app.fitlocal.common.IsoFormats;
import java.time.LocalDate;

/** API shapes for {@code /api/meals}. */
public final class MealDto {

    private MealDto() {
    }

    public record Response(Long id, String mealDate, String food, double calories, double proteinG,
            double carbsG, double fatG, String notes, String createdAt) {

        public static Response from(Meal m) {
            return new Response(m.getId(), IsoFormats.date(m.getMealDate()), m.getFood(), m.getCalories(),
                    m.getProteinG(), m.getCarbsG(), m.getFatG(), m.getNotes(),
                    IsoFormats.dateTime(m.getCreatedAt()));
        }
    }

    /** Create/update body, validated on construction (spec §7.3). */
    public record Request(String mealDate, String food, Double calories, Double proteinG, Double carbsG,
            Double fatG, String notes) {

        public Request {
            Checks.date(mealDate, "Date");
            food = Checks.text(food, "Food", 180, true);
            calories = Checks.number(calories, "Calories", 0, 10000, null);
            proteinG = Checks.number(proteinG, "Protein", 0, 1000, 0.0);
            carbsG = Checks.number(carbsG, "Carbs", 0, 1000, 0.0);
            fatG = Checks.number(fatG, "Fat", 0, 1000, 0.0);
            notes = Checks.text(notes, "Notes", 500, false);
        }

        LocalDate date() {
            return LocalDate.parse(mealDate.strip());
        }

        void applyTo(Meal meal) {
            meal.update(date(), food, calories, proteinG, carbsG, fatG, notes);
        }
    }
}
