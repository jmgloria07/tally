package app.fitlocal.meal;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MealRepository extends JpaRepository<Meal, Long> {

    List<Meal> findAllByOrderByMealDateDescIdDesc(Pageable page);

    List<Meal> findByMealDateBetweenOrderByMealDateDescIdDesc(LocalDate from, LocalDate to);

    List<Meal> findByMealDate(LocalDate date);
}
