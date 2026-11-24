import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

/**
 * Fills a sandbox database with about three weeks of synthetic logs, dated up to today, so the dashboard, weight
 * chart and coach have something to show on camera. A fixed seed makes every run look the same. Synthetic only:
 * never put real log or health data here (phase 1 spec §11).
 * Usage: java -cp "$CP" SeedDemoData.java data/sandbox/tally.db
 * Run it after Tally has started once, so the schema exists. Only writes to a database whose path contains
 * "sandbox", and refuses one that already has meals or workouts, so it can't double up.
 * Settings are left alone: their defaults live in code (C4). Formats match schema.sql: ISO dates (yyyy-MM-dd),
 * timestamps yyyy-MM-dd'T'HH:mm:ss, positions and set numbers 1-based.
 */
public class SeedDemoData {
    static final int DAYS = 21;
    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    record Meal(String food, double kcal, double protein, double carbs, double fat, String notes) {}

    static final List<Meal> BREAKFASTS = List.of(
            new Meal("Oats with banana", 420, 18, 68, 9, "breakfast"),
            new Meal("Eggs and toast", 390, 24, 30, 18, "breakfast"),
            new Meal("Greek yogurt and berries", 310, 25, 35, 7, "breakfast"));
    static final List<Meal> LUNCHES = List.of(
            new Meal("Chicken rice bowl", 730, 45, 95, 15, "lunch"),
            new Meal("Beef and broccoli with rice", 690, 42, 75, 22, "lunch"),
            new Meal("Tuna sandwich and fruit", 560, 34, 68, 14, "lunch"),
            new Meal("Pork adobo with rice", 800, 38, 90, 30, "lunch"));
    // Lunches and dinners keep most days near 2000 kcal while protein stays about 20 g short of 150 g: an
    // on-track week with one clear gap for the coach to act on.
    static final List<Meal> DINNERS = List.of(
            new Meal("Salmon and potatoes", 720, 40, 70, 28, "dinner"),
            new Meal("Chicken tinola with rice", 530, 38, 45, 18, "dinner"),
            new Meal("Pasta with meat sauce", 780, 35, 105, 22, "dinner"),
            new Meal("Tofu stir-fry with rice", 640, 26, 92, 17, "dinner"));
    static final List<Meal> SNACKS = List.of(
            new Meal("Protein shake", 160, 30, 5, 2, "post-workout"),
            new Meal("Apple and peanut butter", 270, 7, 28, 16, "snack"));

    /** Two alternating sessions: {name, base kg, reps per set, sets}. Weight 0 = bodyweight. */
    static final Object[][] SESSION_A = {
            {"Squat", 90.0, 5, 3}, {"Bench Press", 65.0, 5, 3}, {"Barbell Row", 60.0, 8, 3}, {"Plank", 0.0, 1, 2}};
    static final Object[][] SESSION_B = {
            {"Deadlift", 110.0, 5, 2}, {"Overhead Press", 40.0, 6, 3}, {"Pull-up", 0.0, 8, 3}, {"Lunge", 30.0, 10, 2}};

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: SeedDemoData <sandbox db file>");
        }
        String db = args[0].replace('\\', '/');
        if (!db.contains("sandbox")) {
            throw new IllegalArgumentException("refusing " + db + "; only a sandbox database may be seeded");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=rw&busy_timeout=5000")) {
            if (count(c, "meals") > 0 || count(c, "workouts") > 0) {
                throw new IllegalStateException("the sandbox already has logs; reset it first to seed again");
            }
            c.setAutoCommit(false);
            Random random = new Random(42);
            LocalDate today = LocalDate.now();
            int meals = 0, workouts = 0, weighIns = 0, sessions = 0;
            double weight = 74.2;
            for (int back = DAYS - 1; back >= 0; back--) {
                LocalDate day = today.minusDays(back);
                boolean isToday = back == 0;
                // Meals: skip two whole days so the log looks real; today only has breakfast and lunch so far.
                if (back != 9 && back != 16) {
                    meals += meal(c, day, LocalTime.of(7, 30), pick(BREAKFASTS, random));
                    meals += meal(c, day, LocalTime.of(12, 30), pick(LUNCHES, random));
                    if (!isToday) {
                        meals += meal(c, day, LocalTime.of(19, 0), pick(DINNERS, random));
                    }
                }
                // Workouts: three days a week, alternating A and B, adding weight every other session.
                if (back % 7 == 1 || back % 7 == 3 || back % 7 == 5) {
                    Object[][] plan = sessions % 2 == 0 ? SESSION_A : SESSION_B;
                    workout(c, day, plan, 2.5 * (sessions / 2));
                    meals += meal(c, day, LocalTime.of(18, 15), SNACKS.get(0));
                    sessions++;
                    workouts++;
                } else if (!isToday) {
                    meals += meal(c, day, LocalTime.of(15, 30), random.nextInt(3) == 0 ? SNACKS.get(1) : SNACKS.get(0));
                }
                // Weigh-ins: every 2-3 days, trending down about 1 kg over the period. The noise is small so the
                // trend reads clearly and the coach doesn't misread a bounce.
                if (back % 5 == 0 || back % 5 == 2) {
                    double noise = (random.nextInt(3) - 1) / 20.0;
                    insert(c, "INSERT INTO weigh_ins (weigh_date, weight_kg, notes, created_at) VALUES (?, ?, ?, ?)",
                            day.toString(), Math.round((weight + noise) * 10) / 10.0,
                            back % 10 == 0 ? String.format(java.util.Locale.ROOT, "BF: %.1f%%", 18.5 - (DAYS - back) * 0.05) : "",
                            stamp(day, LocalTime.of(6, 45)));
                    weighIns++;
                }
                weight -= 0.05;
            }
            c.commit();
            System.out.printf("Seeded %d meals, %d workouts and %d weigh-ins over %d days ending %s%n",
                    meals, workouts, weighIns, DAYS, today);
        }
    }

    static int meal(Connection c, LocalDate day, LocalTime time, Meal m) throws Exception {
        insert(c, "INSERT INTO meals (meal_date, food, calories, protein_g, carbs_g, fat_g, notes, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                day.toString(), m.food(), m.kcal(), m.protein(), m.carbs(), m.fat(), m.notes(), stamp(day, time));
        return 1;
    }

    static void workout(Connection c, LocalDate day, Object[][] plan, double added) throws Exception {
        long workoutId = insert(c, "INSERT INTO workouts (workout_date, notes, created_at) VALUES (?, ?, ?)",
                day.toString(), plan == SESSION_A ? "Session A" : "Session B", stamp(day, LocalTime.of(18, 0)));
        for (int i = 0; i < plan.length; i++) {
            String name = (String) plan[i][0];
            double base = (double) plan[i][1];
            int reps = (int) plan[i][2];
            int sets = (int) plan[i][3];
            long exerciseId = insert(c, "INSERT INTO workout_exercises (workout_id, position, name, notes) VALUES (?, ?, ?, ?)",
                    workoutId, i + 1, name, name.equals("Plank") ? "60s per set" : "");
            for (int s = 1; s <= sets; s++) {
                insert(c, "INSERT INTO workout_sets (exercise_id, set_no, reps, weight_kg) VALUES (?, ?, ?, ?)",
                        exerciseId, s, reps, base == 0 ? 0.0 : base + added);
            }
        }
    }

    static long insert(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.length; i++) {
                p.setObject(i + 1, values[i]);
            }
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    static int count(Connection c, String table) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.getInt(1);
        }
    }

    static Meal pick(List<Meal> meals, Random random) {
        return meals.get(random.nextInt(meals.size()));
    }

    static String stamp(LocalDate day, LocalTime time) {
        return day.atTime(time).format(TIMESTAMP);
    }
}
