package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.account.AccountService;
import io.github.saksham023.jobagent.account.UserProfile;
import io.github.saksham023.jobagent.api.JobSearch;
import io.github.saksham023.jobagent.auth.TokenService;
import io.github.saksham023.jobagent.geo.Gazetteer;
import io.github.saksham023.jobagent.matching.MatchingProperties;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** A user's job alert as the website shows and saves it, and the "connect Telegram" flow. */
@Service
public class AlertService {

    /**
     * What the alert page needs. saved = false means nothing was saved yet: the filters are suggestions from the
     * profile (families and experience range), alerts off.
     */
    public record View(AlertPrefs prefs, boolean saved, Telegram telegram) {
    }

    /** available = a bot is configured on the server; connected = this user linked a chat; active = not paused. */
    public record Telegram(boolean available, boolean connected, boolean active, String botUsername) {
    }

    private final AlertPrefsRepository prefs;
    private final TelegramLinkRepository links;
    private final TelegramClient telegram;
    private final AccountService accounts;
    private final AlertProperties properties;
    private final SkillExtractor dictionary;
    private final Gazetteer places;
    private final MatchingProperties matching;
    private final SecureRandom random = new SecureRandom();

    public AlertService(AlertPrefsRepository prefs, TelegramLinkRepository links, TelegramClient telegram,
                        AccountService accounts, AlertProperties properties, SkillExtractor dictionary,
                        Gazetteer places, MatchingProperties matching) {
        this.prefs = prefs;
        this.links = links;
        this.telegram = telegram;
        this.accounts = accounts;
        this.properties = properties;
        this.dictionary = dictionary;
        this.places = places;
        this.matching = matching;
    }

    public View view(long userId) {
        Optional<AlertPrefs> saved = prefs.find(userId);
        AlertPrefs shown = saved.orElseGet(() -> suggested(userId));
        Optional<TelegramLinkRepository.Link> link = links.forUser(userId);
        return new View(shown, saved.isPresent(), new Telegram(properties.telegramReady(), link.isPresent(),
                link.map(TelegramLinkRepository.Link::active).orElse(false),
                properties.telegramReady() ? properties.telegram().botUsername() : null));
    }

    public View save(long userId, AlertPrefs requested) {
        AlertPrefs checked = requested.checked();
        prefs.save(userId, new AlertPrefs(checked.enabled(), checked.families(), checked.minYears(), checked.maxYears(),
                checked.includeUnstated(), canonicalSkills(checked.skills()), canonicalCities(checked.cities()),
                knownCompanies(checked.companies())));
        return view(userId);
    }

    /**
     * Skills written the way jobs store them ("java" -> "Java", "springboot" -> "Spring Boot"): the match is an exact
     * "all of" comparison, so an unknown spelling would never match anything; it is refused instead.
     */
    List<String> canonicalSkills(List<String> skills) {
        List<String> canonical = new ArrayList<>();
        for (String skill : skills) {
            String name = dictionary.canonical(skill).map(SkillExtractor.SkillName::skill)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + skill + ". Pick one from the list."));
            if (!canonical.contains(name)) {
                canonical.add(name);
            }
        }
        return List.copyOf(canonical);
    }

    /**
     * Cities written the way jobs store them ("bangalore" -> "Bengaluru"), only cities of the searched country from our
     * place list, plus "Remote"; anything else is refused.
     */
    List<String> canonicalCities(List<String> cities) {
        List<String> canonical = new ArrayList<>();
        for (String city : cities) {
            String name = city.equals(JobSearch.REMOTE) ? JobSearch.REMOTE
                    : places.citiesByName(city).stream()
                            .filter(c -> c.countryCode().equalsIgnoreCase(matching.country()))
                            .map(Gazetteer.City::name)
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown city: " + city + ". Pick one from the list."));
            if (!canonical.contains(name)) {
                canonical.add(name);
            }
        }
        return List.copyOf(canonical);
    }

    /** Only companies we crawl (enabled rows of the companies table). */
    List<String> knownCompanies(List<String> slugs) {
        if (slugs.isEmpty()) {
            return slugs;
        }
        List<String> known = prefs.knownCompanies(slugs);
        for (String slug : slugs) {
            if (!known.contains(slug)) {
                throw new IllegalArgumentException("Unknown company: " + slug + ". Pick one from the list.");
            }
        }
        return slugs;
    }

    /** The bot link that connects this account: t.me/{bot}?start={one-time token}. */
    public String connectUrl(long userId) {
        if (!properties.telegramReady()) {
            throw new IllegalArgumentException("Telegram alerts are not set up on this server.");
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);      // 32 chars, Telegram allows [A-Za-z0-9_-]
        links.saveToken(TokenService.hash(token), userId, Instant.now().plus(properties.telegram().linkTokenTtl()));
        return "https://t.me/" + properties.telegram().botUsername() + "?start=" + token;
    }

    /** Called by the bot for "/start TOKEN": links the chat to the token's account. */
    public Optional<Long> useLinkToken(String token, long chatId) {
        if (token.isBlank() || token.length() > 64) {
            return Optional.empty();
        }
        Optional<Long> userId = links.useToken(TokenService.hash(token));
        userId.ifPresent(id -> links.link(id, chatId));
        return userId;
    }

    public void disconnect(long userId) {
        links.unlink(userId);
    }

    /** A test message, so the user sees the connection works. */
    public void test(long userId) {
        TelegramLinkRepository.Link link = links.forUser(userId).filter(TelegramLinkRepository.Link::active)
                .orElseThrow(() -> new IllegalArgumentException("Connect Telegram first (or send /start to the bot if you paused it)."));
        telegram.sendMessage(link.chatId(), "✅ Test from JobRadar: your alerts will arrive here.");
    }

    /** No alert saved yet: start from the profile's job families and experience range, alerts off. */
    private AlertPrefs suggested(long userId) {
        Optional<UserProfile> profile = accounts.profile(userId);
        List<String> families = profile.map(p -> p.families().stream().map(Enum::name).toList()).orElse(List.of());
        return new AlertPrefs(false, families, profile.map(UserProfile::jobYearsFrom).orElse(null),
                profile.map(UserProfile::jobYearsTo).orElse(null), true, List.of(), List.of(), List.of());
    }
}
