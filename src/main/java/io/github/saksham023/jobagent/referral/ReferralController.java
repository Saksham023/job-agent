package io.github.saksham023.jobagent.referral;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The signed-in user's referral message template (filled per job in the browser).
 *
 * GET    /api/v1/me/referral-template   the user's own text, or the default
 * PUT    /api/v1/me/referral-template   {text}: save the user's own text (checked; a bad one gets 400 with the reason)
 * DELETE /api/v1/me/referral-template   back to the default
 */
@RestController
@RequestMapping("/api/v1/me/referral-template")
public class ReferralController {

    /** custom = the user rewrote it; defaultText and placeholders are for the editor. */
    public record View(String text, boolean custom, String defaultText, List<String> placeholders) {
    }

    public record Body(String text) {
    }

    private final ReferralRepository templates;

    public ReferralController(ReferralRepository templates) {
        this.templates = templates;
    }

    @GetMapping
    public View get(@AuthenticationPrincipal Jwt jwt) {
        return view(userId(jwt));
    }

    @PutMapping
    public View save(@AuthenticationPrincipal Jwt jwt, @RequestBody Body body) {
        String text = ReferralTemplate.check(body.text());
        long userId = userId(jwt);
        if (text.equals(ReferralTemplate.DEFAULT)) {
            templates.delete(userId);
        } else {
            templates.save(userId, text);
        }
        return view(userId);
    }

    @DeleteMapping
    public View reset(@AuthenticationPrincipal Jwt jwt) {
        long userId = userId(jwt);
        templates.delete(userId);
        return view(userId);
    }

    private View view(long userId) {
        return templates.find(userId)
                .map(text -> new View(text, true, ReferralTemplate.DEFAULT, ReferralTemplate.PLACEHOLDERS))
                .orElseGet(() -> new View(ReferralTemplate.DEFAULT, false, ReferralTemplate.DEFAULT, ReferralTemplate.PLACEHOLDERS));
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
