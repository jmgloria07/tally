package app.fitlocal.weighin;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeighInRepository extends JpaRepository<WeighIn, Long> {

    List<WeighIn> findAllByOrderByWeighDateDescIdDesc(Pageable page);

    List<WeighIn> findByWeighDateBetweenOrderByWeighDateDescIdDesc(LocalDate from, LocalDate to);

    Optional<WeighIn> findFirstByWeighDateLessThanEqualOrderByWeighDateDescIdDesc(LocalDate date);

    Optional<WeighIn> findFirstByWeighDateBeforeOrderByWeighDateDescIdDesc(LocalDate date);
}
