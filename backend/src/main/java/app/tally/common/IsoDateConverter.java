package app.tally.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDate;

/**
 * Stores {@link LocalDate} as ISO {@code YYYY-MM-DD} text. Without this, sqlite-jdbc binds dates as epoch
 * milliseconds, which would not be readable or sort as text (spec C13).
 */
@Converter(autoApply = true)
public class IsoDateConverter implements AttributeConverter<LocalDate, String> {

    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        return IsoFormats.date(attribute);
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        return dbData == null ? null : LocalDate.parse(dbData);
    }
}
