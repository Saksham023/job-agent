package io.github.saksham023.jobagent.account;

import io.github.saksham023.jobagent.requirements.JobFamily;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Category;
import io.github.saksham023.jobagent.requirements.SkillExtractor.SkillName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Cleans profile facts, the same way whether a model read them from a resume or the user typed them: known skill
 * names are written the way our dictionary writes them (so "springboot" becomes "Spring Boot"), main languages must
 * be programming languages, sane ranges and lengths, no UNCLASSIFIED family.
 */
public final class ProfileChecks {

    static final double MAX_YEARS = 50;
    static final int MAX_LANGUAGES = 4;
    static final int MAX_SKILLS = 30;
    static final int MAX_SKILL_LENGTH = 40;
    static final int MAX_ROLES_LENGTH = 200;
    static final int MAX_FAMILIES = 4;
    static final int MAX_HEADLINE_LENGTH = 60;
    static final int MAX_BUILD_LENGTH = 120;
    static final int MAX_BUILD_WORDS = 15;          // the model is asked for 12; a little room for the user's own edits

    private ProfileChecks() {
    }

    public static UserProfile.Facts clean(UserProfile.Facts facts, SkillExtractor dictionary) {
        String headline = headline(facts.headline());
        String build = build(facts.build());
        Double years = facts.years();
        if (years != null && (years.isNaN() || years < 0 || years > MAX_YEARS)) {
            years = null;
        }
        if (years != null) {
            years = Math.round(years * 10) / 10.0;
        }
        List<String> languages = new ArrayList<>();
        for (String name : nonNull(facts.mainLanguages())) {
            Optional<SkillName> known = dictionary.canonical(name);
            if (known.isPresent() && known.get().category() == Category.LANGUAGE && !containsIgnoreCase(languages, known.get().skill())
                    && languages.size() < MAX_LANGUAGES) {
                languages.add(known.get().skill());
            }
        }
        Map<String, String> skills = new LinkedHashMap<>();         // lower-case key -> how it is written
        for (String name : nonNull(facts.skills())) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String written = dictionary.canonical(name.strip()).map(SkillName::skill).orElse(name.strip());
            if (written.length() <= MAX_SKILL_LENGTH && skills.size() < MAX_SKILLS) {
                skills.putIfAbsent(written.toLowerCase(Locale.ROOT), written);
            }
        }
        String roles = facts.rolesWanted() == null ? null : facts.rolesWanted().strip().replaceAll("\\s+", " ");
        if (roles != null && roles.isEmpty()) {
            roles = null;
        }
        if (roles != null && roles.length() > MAX_ROLES_LENGTH) {
            roles = roles.substring(0, MAX_ROLES_LENGTH).strip();
        }
        LinkedHashSet<JobFamily> families = new LinkedHashSet<>();
        for (JobFamily family : nonNull(facts.families())) {
            if (family != null && family != JobFamily.UNCLASSIFIED && families.size() < MAX_FAMILIES) {
                families.add(family);
            }
        }
        return new UserProfile.Facts(headline, build, years, List.copyOf(languages), List.copyOf(skills.values()), roles, List.copyOf(families));
    }

    /** "A Backend engineer." -> "Backend engineer": the message adds its own "a" / "an"; null when empty or too long. */
    static String headline(String text) {
        if (text == null) {
            return null;
        }
        String headline = text.strip().replaceAll("\\s+", " ").replaceAll("^(?i)(a|an)\\s+", "").replaceAll("[.!,;:]+$", "").strip();
        return headline.isEmpty() || headline.length() > MAX_HEADLINE_LENGTH ? null : headline;
    }

    /**
     * "Building APIs in Java." -> "building APIs in Java": it follows "years of experience", so it must start with an -ing
     * verb; null when empty, too long (characters or words) or not starting that way.
     */
    static String build(String text) {
        if (text == null) {
            return null;
        }
        String build = text.strip().replaceAll("\\s+", " ").replaceAll("[.!,;:]+$", "").strip();
        if (build.length() > 1 && Character.isUpperCase(build.charAt(0)) && Character.isLowerCase(build.charAt(1))) {
            build = Character.toLowerCase(build.charAt(0)) + build.substring(1);
        }
        boolean verb = build.matches("[a-z]+ing\\b.*");
        boolean tooLong = build.length() > MAX_BUILD_LENGTH || build.split(" ").length > MAX_BUILD_WORDS;
        return build.isEmpty() || tooLong || !verb ? null : build;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        return list.stream().anyMatch(v -> v.equalsIgnoreCase(value));
    }
}
