package app.tally.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestedMessageRepository extends JpaRepository<IngestedMessage, String> {
}
