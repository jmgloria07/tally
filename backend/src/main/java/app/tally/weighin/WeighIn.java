package app.tally.weighin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A body-weight reading. Body-fat or muscle readings live in the free-text notes. */
@Entity
@Table(name = "weigh_ins")
public class WeighIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "weigh_date", nullable = false)
    private LocalDate weighDate;

    @Column(name = "weight_kg", nullable = false)
    private double weightKg;

    @Column(nullable = false)
    private String notes = "";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected WeighIn() {
    }

    public WeighIn(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void update(LocalDate weighDate, double weightKg, String notes) {
        this.weighDate = weighDate;
        this.weightKg = weightKg;
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getWeighDate() {
        return weighDate;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public String getNotes() {
        return notes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
