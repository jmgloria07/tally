package app.fitlocal.settings;

import app.fitlocal.common.Numbers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings defaults live in code, never in the database (spec §4.3). Rows are written only when the user
 * saves Goals & Preferences, so a fresh DB keeps {@code settings} empty.
 */
@Service
public class SettingsService {

    public static final String CALORIES_TARGET = "calories_target";
    public static final String PROTEIN_TARGET = "protein_target";
    public static final String CARBS_TARGET = "carbs_target";
    public static final String FAT_TARGET = "fat_target";
    public static final String GOAL = "goal";
    public static final String DIETARY_PREFERENCES = "dietary_preferences";
    public static final String INJURIES_LIMITATIONS = "injuries_limitations";
    public static final String COACHING_STYLE = "coaching_style";

    public static final Settings DEFAULTS = new Settings(
            2000, 150, 220, 60,
            "Build strength and improve body composition",
            "",
            "",
            "Practical, direct, encouraging; prioritize consistency over perfection.");

    private final SettingRepository repository;

    public SettingsService(SettingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Settings current() {
        Map<String, String> stored = repository.findAll().stream()
                .collect(Collectors.toMap(Setting::getKey, Setting::getValue));
        return new Settings(
                number(stored.get(CALORIES_TARGET), DEFAULTS.caloriesTarget()),
                number(stored.get(PROTEIN_TARGET), DEFAULTS.proteinTarget()),
                number(stored.get(CARBS_TARGET), DEFAULTS.carbsTarget()),
                number(stored.get(FAT_TARGET), DEFAULTS.fatTarget()),
                stored.getOrDefault(GOAL, DEFAULTS.goal()),
                stored.getOrDefault(DIETARY_PREFERENCES, DEFAULTS.dietaryPreferences()),
                stored.getOrDefault(INJURIES_LIMITATIONS, DEFAULTS.injuriesLimitations()),
                stored.getOrDefault(COACHING_STYLE, DEFAULTS.coachingStyle()));
    }

    @Transactional
    public Settings replace(SettingsDto.Request request) {
        Settings settings = request.toSettings();
        Map<String, String> rows = toRows(settings);
        Map<String, Setting> existing = repository.findAllById(rows.keySet()).stream()
                .collect(Collectors.toMap(Setting::getKey, Function.identity()));
        rows.forEach((key, value) -> {
            Setting row = existing.get(key);
            if (row == null) {
                repository.save(new Setting(key, value));
            } else {
                row.setValue(value);
            }
        });
        return settings;
    }

    static Map<String, String> toRows(Settings s) {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(CALORIES_TARGET, Numbers.canonical(s.caloriesTarget()));
        rows.put(PROTEIN_TARGET, Numbers.canonical(s.proteinTarget()));
        rows.put(CARBS_TARGET, Numbers.canonical(s.carbsTarget()));
        rows.put(FAT_TARGET, Numbers.canonical(s.fatTarget()));
        rows.put(GOAL, s.goal());
        rows.put(DIETARY_PREFERENCES, s.dietaryPreferences());
        rows.put(INJURIES_LIMITATIONS, s.injuriesLimitations());
        rows.put(COACHING_STYLE, s.coachingStyle());
        return rows;
    }

    /** A stored value that is not a number falls back to the default rather than breaking every page. */
    private static double number(String stored, double fallback) {
        if (stored == null) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(stored.strip());
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
