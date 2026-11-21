package app.tally.dashboard;

import app.tally.common.ClockConfig;
import app.tally.common.IsoFormats;
import app.tally.common.Numbers;
import app.tally.dashboard.DashboardDto.Activity;
import app.tally.dashboard.DashboardDto.Macros;
import app.tally.dashboard.DashboardDto.WeightPoint;
import app.tally.meal.Meal;
import app.tally.meal.MealRepository;
import app.tally.settings.Settings;
import app.tally.settings.SettingsService;
import app.tally.weighin.WeighIn;
import app.tally.weighin.WeighInRepository;
import app.tally.workout.Workout;
import app.tally.workout.WorkoutExercise;
import app.tally.workout.WorkoutRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the Overview tab shows, in one call. Weekly figures use today and the six days before it; the
 * weight chart uses the last 30 days. Dates are the server's local date; future-dated rows are excluded (spec I3).
 */
@Service
public class DashboardService {

    static final int RECENT_ACTIVITY_ITEMS = 7;
    static final int WEIGHT_CHART_DAYS = 30;

    private final SettingsService settingsService;
    private final MealRepository mealRepository;
    private final WorkoutRepository workoutRepository;
    private final WeighInRepository weighInRepository;
    private final Clock clock;

    public DashboardService(SettingsService settingsService, MealRepository mealRepository,
            WorkoutRepository workoutRepository, WeighInRepository weighInRepository, Clock clock) {
        this.settingsService = settingsService;
        this.mealRepository = mealRepository;
        this.workoutRepository = workoutRepository;
        this.weighInRepository = weighInRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardDto dashboard() {
        LocalDate today = ClockConfig.today(clock);
        LocalDate from = today.minusDays(6);
        Settings settings = settingsService.current();

        List<Meal> weekMeals = mealRepository.findByMealDateBetweenOrderByMealDateDescIdDesc(from, today);
        Macros todayTotals = totals(weekMeals.stream().filter(m -> m.getMealDate().equals(today)).toList());

        Map<LocalDate, List<Meal>> mealsByDay = weekMeals.stream()
                .collect(Collectors.groupingBy(Meal::getMealDate, TreeMap::new, Collectors.toList()));
        double avgCalories = mealsByDay.values().stream()
                .mapToDouble(day -> day.stream().mapToDouble(Meal::getCalories).sum()).average().orElse(0);
        double avgProtein = mealsByDay.values().stream()
                .mapToDouble(day -> day.stream().mapToDouble(Meal::getProteinG).sum()).average().orElse(0);

        int workoutDays = (int) workoutRepository.findByWorkoutDateBetweenOrderByWorkoutDateDescIdDesc(from, today)
                .stream().map(Workout::getWorkoutDate).distinct().count();

        WeightPoint latest = weighInRepository.findFirstByWeighDateLessThanEqualOrderByWeighDateDescIdDesc(today)
                .map(DashboardService::point).orElse(null);
        List<WeightPoint> weightLast30 = weighInRepository
                .findByWeighDateBetweenOrderByWeighDateDescIdDesc(today.minusDays(WEIGHT_CHART_DAYS - 1), today)
                .reversed().stream().map(DashboardService::point).toList();

        Macros targets = new Macros(settings.caloriesTarget(), settings.proteinTarget(), settings.carbsTarget(),
                settings.fatTarget());

        return new DashboardDto(
                IsoFormats.date(today),
                targets,
                todayTotals,
                workoutDays,
                latest,
                weightLast30,
                Numbers.round1(avgCalories),
                Numbers.round1(avgProtein),
                recentActivity());
    }

    private static Macros totals(List<Meal> meals) {
        return new Macros(
                Numbers.round1(meals.stream().mapToDouble(Meal::getCalories).sum()),
                Numbers.round1(meals.stream().mapToDouble(Meal::getProteinG).sum()),
                Numbers.round1(meals.stream().mapToDouble(Meal::getCarbsG).sum()),
                Numbers.round1(meals.stream().mapToDouble(Meal::getFatG).sum()));
    }

    private static WeightPoint point(WeighIn w) {
        return new WeightPoint(IsoFormats.date(w.getWeighDate()), w.getWeightKg());
    }

    /** Up to seven newest items across workouts, meals and weigh-ins, by date then creation time. */
    private List<Activity> recentActivity() {
        record Item(LocalDate date, LocalDateTime createdAt, Activity activity) {
        }
        PageRequest page = PageRequest.of(0, RECENT_ACTIVITY_ITEMS);
        List<Item> items = new ArrayList<>();
        for (Workout w : workoutRepository.findAllByOrderByWorkoutDateDescIdDesc(page)) {
            items.add(new Item(w.getWorkoutDate(), w.getCreatedAt(), workoutActivity(w)));
        }
        for (Meal m : mealRepository.findAllByOrderByMealDateDescIdDesc(page)) {
            String detail = Numbers.oneDecimal(m.getCalories()) + " kcal · "
                    + Numbers.oneDecimal(m.getProteinG()) + " g protein";
            items.add(new Item(m.getMealDate(), m.getCreatedAt(),
                    new Activity("meal", IsoFormats.date(m.getMealDate()), m.getFood(), detail)));
        }
        for (WeighIn w : weighInRepository.findAllByOrderByWeighDateDescIdDesc(page)) {
            String detail = w.getNotes().isBlank() ? "Weigh-in recorded" : w.getNotes();
            items.add(new Item(w.getWeighDate(), w.getCreatedAt(), new Activity("weighIn",
                    IsoFormats.date(w.getWeighDate()), "Body weight: " + Numbers.oneDecimal(w.getWeightKg()) + " kg",
                    detail)));
        }
        return items.stream()
                .sorted(Comparator.comparing(Item::date).thenComparing(Item::createdAt).reversed())
                .limit(RECENT_ACTIVITY_ITEMS)
                .map(Item::activity)
                .toList();
    }

    private static Activity workoutActivity(Workout w) {
        List<WorkoutExercise> exercises = w.getExercises();
        int count = exercises.size();
        String title = "Workout — " + count + (count == 1 ? " exercise" : " exercises");
        String detail = exercises.stream().limit(3).map(WorkoutExercise::getName)
                .collect(Collectors.joining(", ")) + (count > 3 ? ", …" : "");
        return new Activity("workout", IsoFormats.date(w.getWorkoutDate()), title, detail);
    }
}
