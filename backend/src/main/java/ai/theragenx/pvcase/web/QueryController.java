package ai.theragenx.pvcase.web;

import ai.theragenx.pvcase.query.CreateQueryRequest;
import ai.theragenx.pvcase.query.QueryService;
import ai.theragenx.pvcase.query.ReviewerQuery;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reviewer queries raised against individual case fields. */
@RestController
@RequestMapping("/queries")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /** 201 with the created query, including the id the UI needs to reference it. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewerQuery createQuery(@Valid @RequestBody CreateQueryRequest request) {
        return queryService.create(request);
    }

    /**
     * Queries for one case.
     *
     * <p>{@code caseId} is required rather than optional-with-a-default. An
     * unscoped list of every query in the system is not something the reviewer
     * screen ever wants, and making it the accidental result of a forgotten
     * parameter is how you end up shipping one.
     */
    @GetMapping
    public List<ReviewerQuery> listQueries(@RequestParam String caseId) {
        return queryService.findByCaseId(caseId);
    }
}
