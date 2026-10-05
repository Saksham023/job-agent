package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.matching.MatchService.MatchResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Matching for testing by hand before the MCP tool exists. Local use only (no authentication).
 */
@RestController
@RequestMapping("/admin/match")
public class MatchController {

    private final MatchService matchService;

    public MatchController(MatchService matchService) {
        this.matchService = matchService;
    }

    /**
     * POST a Profile as JSON; returns the best `limit` jobs with their score breakdown.
     * Optional postedSince=YYYY-MM-DD keeps only jobs posted since that day (India time).
     */
    @PostMapping
    public MatchResponse match(@Valid @RequestBody Profile profile,
                               @RequestParam(defaultValue = "20") int limit,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                               LocalDate postedSince) {
        return matchService.match(profile, Math.min(Math.max(limit, 1), 100), postedSince == null ? null
                : postedSince.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant());
    }
}