package ai.theragenx.pvcase.merge;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single field as it arrived in a payload, after normalisation but before
 * merging.
 *
 * <p>{@code confidence} and {@code source} are null when the payload supplied a
 * bare scalar (e.g. {@code "age": 63}) instead of the full
 * {@code {value, confidence, source}} envelope. They are left null on purpose:
 * borrowing the previous version's provenance for a newly supplied value would
 * misattribute where that value came from.
 */
public record IncomingField(JsonNode value, Double confidence, String source) {
}
