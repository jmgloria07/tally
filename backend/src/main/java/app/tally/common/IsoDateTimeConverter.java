package app.tally.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDateTime;

/** Stores {@link LocalDateTime} as ISO local text with seconds precision (spec C13). */
@Converter(autoApply = true)
public class IsoDateTimeConverter implements AttributeConverter<LocalDateTime, String> {

    @Override
    public String convertToDatabaseColumn(LocalDateTime attribute) {
        return IsoFormats.dateTime(attribute);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String dbData) {
        return IsoFormats.parseDateTime(dbData);
    }
}
