package io.github.saksham023.jobagent.profile;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved profiles over HTTP, for testing with curl. Local use only.
 *
 * POST /admin/profiles        (body: ProfileFacts)  saves a new profile, returns it with its id
 * GET  /admin/profiles/{id}                         the stored facts and the last search's preferences
 */
@RestController
@RequestMapping("/admin/profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedProfile create(@RequestBody ProfileFacts facts) {
        return profileService.create(facts, "api");
    }

    @GetMapping("/{id}")
    public SavedProfile get(@PathVariable String id) {
        return profileService.get(id);
    }
}
