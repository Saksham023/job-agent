package io.github.saksham023.jobagent.search;

import io.github.saksham023.jobagent.matching.Profile;
import io.github.saksham023.jobagent.search.SearchService.ExportRow;
import io.github.saksham023.jobagent.search.SearchService.SearchPage;
import jakarta.validation.Valid;
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
 * POST /admin/search?wants=backend%20roles&pageSize=10   (body: a Profile)  starts a search, first answer
 * GET  /admin/search/{id}/more?count=10                                       the next jobs
 * GET  /admin/search/{id}/export?includeMaybe=false                           company, title, link of every APPLY job
 */
@RestController
@RequestMapping("/admin/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public SearchPage start(@Valid @RequestBody Profile profile,
                            @RequestParam(required = false) String wants,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate postedSince,
                            @RequestParam(required = false) Integer pageSize) {
        return searchService.start(profile, wants, postedSince == null ? null
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