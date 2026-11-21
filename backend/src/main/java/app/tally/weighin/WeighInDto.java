package app.tally.weighin;

import app.tally.common.Checks;
import app.tally.common.IsoFormats;
import java.time.LocalDate;

/** API shapes for {@code /api/weigh-ins}. */
public final class WeighInDto {

    private WeighInDto() {
    }

    public record Response(Long id, String weighDate, double weightKg, String notes, String createdAt) {

        public static Response from(WeighIn w) {
            return new Response(w.getId(), IsoFormats.date(w.getWeighDate()), w.getWeightKg(), w.getNotes(),
                    IsoFormats.dateTime(w.getCreatedAt()));
        }
    }

    /** Create/update body, validated on construction (spec §7.3). */
    public record Request(String weighDate, Double weightKg, String notes) {

        public Request {
            Checks.date(weighDate, "Date");
            weightKg = Checks.number(weightKg, "Weight", 20, 500, null);
            notes = Checks.text(notes, "Notes", 500, false);
        }

        void applyTo(WeighIn weighIn) {
            weighIn.update(LocalDate.parse(weighDate.strip()), weightKg, notes);
        }
    }
}
