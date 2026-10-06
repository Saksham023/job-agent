package io.github.saksham023.jobagent.search;

import io.github.saksham023.jobagent.profile.ProfileFacts;
import io.github.saksham023.jobagent.profile.ProfileService;
import io.github.saksham023.jobagent.profile.SearchPreferences;
import io.github.saksham023.jobagent.requirements.JobFamily;
import io.github.saksham023.jobagent.search.SearchService.ExportRow;
import io.github.saksham023.jobagent.search.SearchService.SearchPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The judged search over HTTP, for testing with curl (the MCP tools call the same service). Local use only.
 *
 * POST /admin/search?wants=backend%20roles&pageSize=10   (body: resume facts + preferences)  new profile + search
 * POST /admin/search?profileId=p-7k3x9q2m                 (body: preferences only, or {})  search a saved profile
 * GET  /admin/search/{id}/more?count=10                                       the next jobs
 * GET  /admin/search/{id}/export?includeMaybe=false                           company, title, link of every APPLY job
 */
@RestController
@RequestMapping("/admin/search")
public class SearchController {

    /** The body: the resume facts (leave them out with ?profileId) and this search's preferences. */
    public record SearchRequest(@DecimalMin("0") @DecimalMax("50") Double yearsOfExperience, List<String> skills,
                                List<String> primaryLanguages, String wants, List<String> preferredLocations,
                                Boolean openToRemote, List<JobFamily> families, Integer jobYearsFrom,
                                Integer jobYearsTo) {
    }

    private final SearchService searchService;
    private final ProfileService profileService;

    public SearchController(SearchService searchService, ProfileService profileService) {
        this.searchService = searchService;
        this.profileService = profileService;
    }

    /** wants may come in the body or as a query parameter (the body wins). */
    @PostMapping
    public SearchPage start(@Valid @RequestBody SearchRequest request,
                            @RequestParam(required = false) String profileId,
                            @RequestParam(required = false) String wants,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate postedSince,
                            @RequestParam(required = false) Integer pageSize) {
        ProfileService.Resolved resolved = profileService.resolve(profileId,
                new ProfileFacts(request.yearsOfExperience(), request.skills(), request.primaryLanguages(),
                        request.wants() != null ? request.wants() : wants),
                new SearchPreferences(request.preferredLocations(), request.openToRemote(), request.families(),
                        request.jobYearsFrom(), request.jobYearsTo()), "api");
        return searchService.start(resolved, postedSince == null ? null
                : postedSince.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant(), pageSize);
    }

    @GetMapping("/{id}/more")
    public SearchPage more(@PathVariable UUID id, @RequestParam(required = false) Integer count) {
        return searchService.more(id, count);
    }

    @GetMapping("/{id}/export")
    public List<ExportRow> export(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeMaybe) {
        return searchService.export(id, includeMaybe);
    }
}