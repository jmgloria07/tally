package app.tally.meal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "meals")
public class Meal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meal_date", nullable = false)
    private LocalDate mealDate;

    @Column(nullable = false)
    private String food;

    @Column(nullable = false)
    private double calories;

    @Column(name = "protein_g", nullable = false)
    private double proteinG;

    @Column(name = "carbs_g", nullable = false)
    private double carbsG;

    @Column(name = "fat_g", nullable = false)
    private double fatG;

    @Column(nullable = false)
    private String notes = "";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Meal() {
    }

    public Meal(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void update(LocalDate mealDate, String food, double calories, double proteinG, double carbsG,
            double fatG, String notes) {
        this.mealDate = mealDate;
        this.food = food;
        this.calories = calories;
        this.proteinG = proteinG;
        this.carbsG = carbsG;
        this.fatG = fatG;
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getMealDate() {
        return mealDate;
    }

    public String getFood() {
        return food;
    }

    public double getCalories() {
        return calories;
    }

    public double getProteinG() {
        return proteinG;
    }

    public double getCarbsG() {
        return carbsG;
    }

    public double getFatG() {
        return fatG;
    }

    public String getNotes() {
        return notes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
