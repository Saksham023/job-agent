package io.github.saksham023.jobagent.account;

import io.github.saksham023.jobagent.requirements.JobFamily;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The signed-in user's own profile.
 *
 * GET  /api/v1/me/profile         the profile, or 204 when there is none yet (the UI then offers the resume dialog)
 * PUT  /api/v1/me/profile         the user's edits {headline, build, years, mainLanguages, skills, rolesWanted, families, driveLink}
 * POST /api/v1/me/resume/link     {link}: read the resume behind a Google Drive / Docs link (recommended)
 * POST /api/v1/me/resume/upload   multipart "file": read an uploaded PDF
 */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

    /** What the profile form sends; families as names. */
    public record Edit(String headline, String build, Double years, List<String> mainLanguages, List<String> skills, String rolesWanted,
                       List<String> families, String driveLink) {
    }

    public record LinkBody(String link) {
    }

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/profile")
    public ResponseEntity<UserProfile> profile(@AuthenticationPrincipal Jwt jwt) {
        return accounts.profile(userId(jwt)).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/profile")
    public UserProfile edit(@AuthenticationPrincipal Jwt jwt, @RequestBody Edit edit) {
        List<JobFamily> families = new ArrayList<>();
        for (String name : edit.families() == null ? List.<String>of() : edit.families()) {
            try {
                families.add(JobFamily.valueOf(name.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new AccountException(HttpStatus.BAD_REQUEST, "Unknown job family: " + name);
            }
        }
        UserProfile.Facts facts = new UserProfile.Facts(edit.headline(), edit.build(), edit.years(), edit.mainLanguages(), edit.skills(), edit.rolesWanted(), families);
        return accounts.edit(userId(jwt), facts, edit.driveLink());
    }

    @PostMapping("/resume/link")
    public UserProfile readLink(@AuthenticationPrincipal Jwt jwt, @RequestBody LinkBody body) {
        return accounts.readFromLink(userId(jwt), body.link());
    }

    @PostMapping("/resume/upload")
    public UserProfile readUpload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "Please choose a PDF file.");
        }
        return accounts.readFromUpload(userId(jwt), file.getBytes());
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<Map<String, String>> failed(AccountException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
