package io.github.saksham023.jobagent.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in user's marks on one job: its status and, separately, whether it is hidden.
 *
 * PUT    /api/v1/me/jobs/{id}/status   {"status": "APPLIED"}  set or change the status
 * DELETE /api/v1/me/jobs/{id}/status                          clear the status (a hidden job stays hidden)
 * PUT    /api/v1/me/jobs/{id}/hidden                          hide the job (its status stays)
 * DELETE /api/v1/me/jobs/{id}/hidden                          show it again
 */
@RestController
@RequestMapping("/api/v1/me/jobs/{id}")
public class JobStatusController {

    public record Body(String status) {
    }

    private final JobStatusRepository statuses;

    public JobStatusController(JobStatusRepository statuses) {
        this.statuses = statuses;
    }

    @PutMapping("/status")
    public ResponseEntity<Void> set(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody Body body) {
        if (!statuses.set(userId(jwt), id, JobStatus.parse(body == null ? null : body.status()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No job " + id);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/status")
    public ResponseEntity<Void> clear(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        statuses.clear(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/hidden")
    public ResponseEntity<Void> hide(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        if (!statuses.hide(userId(jwt), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No job " + id);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/hidden")
    public ResponseEntity<Void> unhide(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        statuses.unhide(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    /** Marks belong to a user account: the shared admin key has none. */
    private static long userId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in to mark jobs");
        }
        return Long.parseLong(jwt.getSubject());
    }
}
