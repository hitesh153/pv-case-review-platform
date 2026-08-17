package ai.theragenx.pvcase.query;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /queries}.
 *
 * <p>Shape is fixed by the brief. snake_case aliases are accepted as well,
 * because the rest of this API speaks snake_case and a client that guesses
 * consistently should not be punished with a 400 for it.
 */
public record CreateQueryRequest(

        @NotBlank(message = "is required")
        @JsonAlias("case_id")
        String caseId,

        @NotBlank(message = "is required")
        @JsonAlias("field_path")
        String fieldPath,

        @NotBlank(message = "is required")
        @Size(max = 2000, message = "must be at most 2000 characters")
        String question) {
}
