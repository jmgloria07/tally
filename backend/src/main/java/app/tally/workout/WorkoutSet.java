package app.tally.workout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "workout_sets")
public class WorkoutSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercise_id", nullable = false)
    private WorkoutExercise exercise;

    @Column(name = "set_no", nullable = false)
    private int setNo;

    @Column(nullable = false)
    private int reps;

    @Column(name = "weight_kg", nullable = false)
    private double weightKg;

    protected WorkoutSet() {
    }

    WorkoutSet(WorkoutExercise exercise, int setNo, int reps, double weightKg) {
        this.exercise = exercise;
        this.setNo = setNo;
        this.reps = reps;
        this.weightKg = weightKg;
    }

    public int getSetNo() {
        return setNo;
    }

    public int getReps() {
        return reps;
    }

    public double getWeightKg() {
        return weightKg;
    }
}
