package app.tally.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Ledger row: this message's entries were logged (see E2). Written in the same transaction as the entries, so
 * a message is logged at most once even if Tally crashes before reacting ✅.
 */
@Entity
@Table(name = "ingested_messages")
public class IngestedMessage {

    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "ingested_at", nullable = false)
    private LocalDateTime ingestedAt;

    protected IngestedMessage() {
    }

    public IngestedMessage(String messageId, LocalDateTime ingestedAt) {
        this.messageId = messageId;
        this.ingestedAt = ingestedAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public LocalDateTime getIngestedAt() {
        return ingestedAt;
    }
}
