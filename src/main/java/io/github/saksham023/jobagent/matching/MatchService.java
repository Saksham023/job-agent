package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.geo.Gazetteer;
import io.github.saksham023.jobagent.matching.MatchCandidateRepository.Candidate;
import io.github.saksham023.jobagent.matching.MatchCandidateRepository.Criteria;
import io.github.saksham023.jobagent.matching.MatchScorer.Match;
import io.github.saksham023.jobagent.matching.MatchScorer.ResolvedProfile;
import io.github.saksham023.jobagent.requirements.JobFamily;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import io.github.saksham023.jobagent.requirements.SkillExtractor.SkillName;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Matching v1: resolve the profile onto canonical names, filter eligible jobs in SQL, score them, rank them.
 * No LLM and no embeddings; every score comes with its reasons.
 */
@Service
public class MatchService {

    /**
     * @param profile          what the input was understood as (canonical names), so the caller can check it
     * @param unknownSkills    profile skills the dictionary does not know (ignored for scoring)
     * @param unknownLocations profile places the gazetteer does not know (ignored for filtering)
     * @param experienceWindow the years range jobs had to overlap (from the rounded years, or as asked), or null
     * @param postedSince      the "posted since" filter that was applied, or null
     * @param eligible         jobs that passed the hard filters, before ranking
     */
    public record MatchResponse(ResolvedProfile profile, List<String> unknownSkills, List<String> unknownLocations,
                                ExperienceWindow experienceWindow, Instant postedSince, int eligible,
                                List<Match> matches) {
    }

    private final MatchCandidateRepository repository;
    private final MatchScorer scorer;
    private final SkillExtractor skillExtractor;
    private final Gazetteer gazetteer;
    private final SkillImplications implications;
    private final MatchingProperties settings;

    public MatchService(MatchCandidateRepository repository, MatchScorer scorer, SkillExtractor skillExtractor,
                        Gazetteer gazetteer, SkillImplications implications, MatchingProperties settings) {
        this.repository = repository;
        this.scorer = scorer;
        this.skillExtractor = skillExtractor;
        this.gazetteer = gazetteer;
        this.implications = implications;
        this.settings = settings;
    }

    public MatchResponse match(Profile profile, int limit) {
        return match(profile, limit, null);
    }

    /** @param postedSince only jobs posted at or after this instant ("what is new"), or null for all open jobs */
    public MatchResponse match(Profile profile, int limit, Instant postedSince) {
        List<String> unknownSkills = new ArrayList<>();
        List<String> unknownLocations = new ArrayList<>();

        Set<String> skills = new LinkedHashSet<>();
        Set<String> languages = new LinkedHashSet<>();
        for (String name : profile.skills()) {
            Optional<SkillName> skill = skillExtractor.canonical(name);
            skill.ifPresentOrElse(s -> {
                skills.add(s.skill());
                if (s.category() == SkillExtractor.Category.LANGUAGE) {
                    languages.add(s.skill());
                }
            }, () -> unknownSkills.add(name));
        }
        if (!profile.primaryLanguages().isEmpty()) {
            languages.clear();                                        // the candidate said which ones are primary
            profile.primaryLanguages().forEach(name -> skillExtractor.canonical(name).ifPresent(s -> {
                languages.add(s.skill());
                skills.add(s.skill());
            }));
        }

        Set<String> cities = new LinkedHashSet<>();
        for (String place : profile.preferredLocations()) {
            List<String> resolved = resolveCity(place);
            if (resolved.isEmpty()) {
                unknownLocations.add(place);
            }
            cities.addAll(resolved);
        }

        Integer years = ExperienceWindow.roundYears(profile.yearsOfExperience(), settings.roundUpFrom());
        ExperienceWindow window = ExperienceWindow.resolve(years, profile.jobYearsFrom(), profile.jobYearsTo(), settings);

        ResolvedProfile resolved = new ResolvedProfile(years, Set.copyOf(skills),
                Set.copyOf(languages), Set.copyOf(cities), profile.openToRemote(), implications.expand(skills));

        List<Candidate> candidates = repository.find(new Criteria(settings.country(),
                profile.families().stream().map(JobFamily::name).toList(), List.copyOf(cities),
                profile.openToRemote(), window, postedSince));

        List<Match> ranked = candidates.stream()
                .map(candidate -> scorer.score(candidate, resolved))
                .sorted(Comparator.comparingInt(Match::score).reversed().thenComparing(Match::jobId, Comparator.reverseOrder()))
                .limit(limit)
                .toList();

        return new MatchResponse(resolved, List.copyOf(unknownSkills), List.copyOf(unknownLocations), window,
                postedSince, candidates.size(), ranked);
    }

    /** "NCR" -> every city in the metro; "Gurgaon" -> Gurugram (cities of the configured country first); unknown -> empty. */
    private List<String> resolveCity(String place) {
        Optional<Gazetteer.Metro> metro = gazetteer.metroByName(place);
        if (metro.isPresent()) {
            return gazetteer.citiesInMetro(metro.get().key()).stream().map(Gazetteer.City::name).toList();
        }
        return gazetteer.citiesByName(place).stream()
                .sorted(Comparator.comparing((Gazetteer.City c) -> !settings.country().equals(c.countryCode())))
                .map(Gazetteer.City::name)
                .findFirst()
                .map(List::of)
                .orElse(List.of());
    }
}