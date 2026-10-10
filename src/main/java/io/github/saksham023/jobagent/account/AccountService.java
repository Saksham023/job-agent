package io.github.saksham023.jobagent.account;

import io.github.saksham023.jobagent.matching.ExperienceWindow;
import io.github.saksham023.jobagent.matching.MatchingProperties;
import io.github.saksham023.jobagent.requirements.JobFamily;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A signed-in user's profile: read from a resume (a Google Drive link, recommended, or an uploaded PDF), shown back to
 * them, and editable. The PDF is read once and discarded; only the facts (and the link) are stored.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final UserProfileRepository profiles;
    private final DriveFetcher drive;
    private final PdfText pdfText;
    private final ResumeReader reader;
    private final SkillExtractor dictionary;
    private final MatchingProperties matching;
    private final AccountProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final Map<Long, Deque<Instant>> reads = new ConcurrentHashMap<>();

    public AccountService(UserProfileRepository profiles, DriveFetcher drive, PdfText pdfText, ResumeReader reader,
                          SkillExtractor dictionary, MatchingProperties matching, AccountProperties properties) {
        this.profiles = profiles;
        this.drive = drive;
        this.pdfText = pdfText;
        this.reader = reader;
        this.dictionary = dictionary;
        this.matching = matching;
        this.properties = properties;
    }

    public Optional<UserProfile> profile(long userId) {
        return profiles.find(userId).map(this::view);
    }

    /** Downloads the resume behind a Google Drive / Docs link, reads it, and keeps the link for referral messages. */
    public UserProfile readFromLink(long userId, String link) {
        DriveLink parsed = DriveLink.parse(link).orElseThrow(() -> new AccountException(HttpStatus.BAD_REQUEST,
                "Please paste a Google Drive or Google Docs link to your resume."));
        takeRead(userId);
        UserProfile.Facts facts = factsOf(drive.fetch(parsed));
        profiles.saveRead(userId, facts, "drive", link.strip());
        log.info("User {}: resume read from Google Drive ({} skills)", userId, facts.skills().size());
        return profile(userId).orElseThrow();
    }

    /** Reads an uploaded PDF. An earlier Drive link stays (only the facts are replaced). */
    public UserProfile readFromUpload(long userId, byte[] pdf) {
        takeRead(userId);
        UserProfile.Facts facts = factsOf(DriveFetcher.checkedPdf(pdf, properties.maxPdfBytes()));
        profiles.saveRead(userId, facts, "upload", null);
        log.info("User {}: resume read from an upload ({} skills)", userId, facts.skills().size());
        return profile(userId).orElseThrow();
    }

    /** The user's own edits; an empty link removes it, any other link must be a Drive / Docs link. */
    public UserProfile edit(long userId, UserProfile.Facts facts, String driveLink) {
        String link = driveLink == null || driveLink.isBlank() ? null : driveLink.strip();
        if (link != null && DriveLink.parse(link).isEmpty()) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "The resume link must be a Google Drive or Google Docs link.");
        }
        if (facts.build() != null && !facts.build().isBlank() && ProfileChecks.build(facts.build()) == null) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "\"What you build\" should start with a verb like \"building\" and be at most "
                    + ProfileChecks.MAX_BUILD_WORDS + " words.");
        }
        profiles.saveEdit(userId, ProfileChecks.clean(facts, dictionary), link);
        return profile(userId).orElseThrow();
    }

    private UserProfile.Facts factsOf(byte[] pdf) {
        ResumeReader.Answer answer = reader.read(pdfText.read(pdf));
        return ProfileChecks.clean(new UserProfile.Facts(answer.headline(), answer.build(), answer.years(), answer.mainLanguages(), answer.skills(),
                answer.rolesWanted(), answer.families()), dictionary);
    }

    /** Each read is a model call: at most readsPerHour per user. */
    private void takeRead(long userId) {
        Instant now = clock.instant();
        Deque<Instant> recent = reads.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
                recent.pollFirst();
            }
            if (recent.size() >= properties.readsPerHour()) {
                throw new AccountException(HttpStatus.TOO_MANY_REQUESTS, "You've read several resumes in the last hour. Please try again later.");
            }
            recent.addLast(now);
        }
    }

    /** The stored row plus the experience range "Match my resume" filters on (same rule as the AI search). */
    UserProfile view(UserProfileRepository.Row row) {
        Integer years = ExperienceWindow.roundYears(row.facts().years(), matching.roundUpFrom());
        ExperienceWindow window = ExperienceWindow.resolve(years, null, null, matching);
        List<JobFamily> families = row.facts().families();
        return new UserProfile(row.facts().headline(), row.facts().build(), row.facts().years(), row.facts().mainLanguages(), row.facts().skills(),
                row.facts().rolesWanted(), families, row.driveLink(), row.source(), row.readAt(), row.editedAt(),
                window == null ? null : window.from(), window == null ? null : window.to());
    }
}
