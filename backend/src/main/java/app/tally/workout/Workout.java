package app.tally.workout;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** One training session: a date, optional notes, and an ordered list of exercises. */
@Entity
@Table(name = "workouts")
public class Workout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workout_date", nullable = false)
    private LocalDate workoutDate;

    @Column(nullable = false)
    private String notes = "";

    /** Client-generated UUID that makes a retried submit return the first result (spec C7). */
    @Column(name = "submission_id", unique = true)
    private String submissionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "workout", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<WorkoutExercise> exercises = new ArrayList<>();

    protected Workout() {
    }

    public Workout(LocalDate workoutDate, String notes, String submissionId, LocalDateTime createdAt) {
        this.workoutDate = workoutDate;
        this.notes = notes;
        this.submissionId = submissionId;
        this.createdAt = createdAt;
    }

    public WorkoutExercise addExercise(String name, String notes) {
        WorkoutExercise exercise = new WorkoutExercise(this, exercises.size() + 1, name, notes);
        exercises.add(exercise);
        return exercise;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getWorkoutDate() {
        return workoutDate;
    }

    public void setWorkoutDate(LocalDate workoutDate) {
        this.workoutDate = workoutDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getSubmissionId() {
        return submissionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<WorkoutExercise> getExercises() {
        return exercises;
    }
}
