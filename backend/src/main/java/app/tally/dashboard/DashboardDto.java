package app.tally.dashboard;

import java.util.List;

/**
 * Shape of {@code GET /api/dashboard} (spec §7.4). Training days and averages cover the last 7 days; the weight
 * series covers the last 30 days for the chart.
 */
public record DashboardDto(
        String today,
        Macros targets,
        Macros todayTotals,
        int workoutDaysLast7,
        WeightPoint latestWeighIn,
        List<WeightPoint> weightLast30,
        double weekAvgCaloriesPerLoggedDay,
        double weekAvgProteinPerLoggedDay,
        List<Activity> recentActivity) {

    public record Macros(double calories, double proteinG, double carbsG, double fatG) {
    }

    public record WeightPoint(String date, double weightKg) {
    }

    /** {@code kind} is {@code workout}, {@code meal} or {@code weighIn}. */
    public record Activity(String kind, String date, String title, String detail) {
    }
}
