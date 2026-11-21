package app.tally.workout;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "workout_exercises")
public class WorkoutExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workout_id", nullable = false)
    private Workout workout;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String notes = "";

    @OneToMany(mappedBy = "exercise", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("setNo")
    private List<WorkoutSet> sets = new ArrayList<>();

    protected WorkoutExercise() {
    }

    WorkoutExercise(Workout workout, int position, String name, String notes) {
        this.workout = workout;
        this.position = position;
        this.name = name;
        this.notes = notes;
    }

    public void addSet(int reps, double weightKg) {
        sets.add(new WorkoutSet(this, sets.size() + 1, reps, weightKg));
    }

    public Long getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public String getName() {
        return name;
    }

    public String getNotes() {
        return notes;
    }

    public List<WorkoutSet> getSets() {
        return sets;
    }
}
