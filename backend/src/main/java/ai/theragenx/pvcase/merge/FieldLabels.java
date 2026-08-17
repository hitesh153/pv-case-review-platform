package ai.theragenx.pvcase.merge;

import java.util.Locale;
import java.util.Map;

/**
 * Turns snake_case field and section keys into something a human reads.
 *
 * <p>The server derives labels rather than leaving it to the client for one
 * reason: sections and fields can be arbitrary, so the UI cannot ship a
 * hardcoded lookup for keys that do not exist yet. A field named
 * {@code rechallenge_result} appearing for the first time in a follow-up should
 * still render as "Rechallenge Result" without a frontend change.
 */
public final class FieldLabels {

    /** Only where the generic rule reads badly — mostly units and initialisms. */
    private static final Map<String, String> OVERRIDES = Map.of(
            "weight_kg", "Weight (kg)",
            "height_cm", "Height (cm)",
            "dob", "Date of Birth",
            "meddra_pt", "MedDRA Preferred Term",
            "who_drl", "WHO Drug Reference"
    );

    private FieldLabels() {
    }

    public static String humanize(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String normalised = key.trim().toLowerCase(Locale.ROOT);
        String override = OVERRIDES.get(normalised);
        if (override != null) {
            return override;
        }

        String[] words = normalised.split("[_\\-\\s]+");
        StringBuilder label = new StringBuilder(normalised.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }
}
