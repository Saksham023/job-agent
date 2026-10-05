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

    static final String COUNTRY = "IN";
    static final int UNDERQUALIFIED_BY = 1;
    static final int OVERQUALIFIED_BY = 3;

    /**
     * @param profile          what the input was understood as (canonical names), so the caller can check it
     * @param unknownSkills    profile skills the dictionary does not know (ignored for scoring)
     * @param unknownLocations profile places the gazetteer does not know (ignored for filtering)
     * @param eligible         jobs that passed the hard filters, before ranking
     */
    public record MatchResponse(ResolvedProfile profile, List<String> unknownSkills, List<String> unknownLocations,
                                int eligible, List<Match> matches) {
    }

    private final MatchCandidateRepository repository;
    private final MatchScorer scorer;
    private final SkillExtractor skillExtractor;
    private final Gazetteer gazetteer;
    private final SkillImplications implications;

    public MatchService(MatchCandidateRepository repository, MatchScorer scorer, SkillExtractor skillExtractor,
                        Gazetteer gazetteer, SkillImplications implications) {
        this.repository = repository;
        this.scorer = scorer;
        this.skillExtractor = skillExtractor;
        this.gazetteer = gazetteer;
        this.implications = implications;
    }

    public MatchResponse match(Profile profile, int limit) {
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

        ResolvedProfile resolved = new ResolvedProfile(profile.yearsOfExperience(), Set.copyOf(skills),
                Set.copyOf(languages), Set.copyOf(cities), profile.openToRemote(), implications.expand(skills));

        List<Candidate> candidates = repository.find(new Criteria(COUNTRY,
                profile.families().stream().map(JobFamily::name).toList(), List.copyOf(cities),
                profile.openToRemote(), profile.yearsOfExperience(), UNDERQUALIFIED_BY, OVERQUALIFIED_BY));

        List<Match> ranked = candidates.stream()
                .map(candidate -> scorer.score(candidate, resolved))
                .sorted(Comparator.comparingInt(Match::score).reversed().thenComparing(Match::jobId, Comparator.reverseOrder()))
                .limit(limit)
                .toList();

        return new MatchResponse(resolved, List.copyOf(unknownSkills), List.copyOf(unknownLocations),
                candidates.size(), ranked);
    }

    /** "NCR" -> every city in the metro; "Gurgaon" -> Gurugram (Indian cities first); unknown -> empty. */
    private List<String> resolveCity(String place) {
        Optional<Gazetteer.Metro> metro = gazetteer.metroByName(place);
        if (metro.isPresent()) {
            return gazetteer.citiesInMetro(metro.get().key()).stream().map(Gazetteer.City::name).toList();
        }
        return gazetteer.citiesByName(place).stream()
                .sorted(Comparator.comparing((Gazetteer.City c) -> !COUNTRY.equals(c.countryCode())))
                .map(Gazetteer.City::name)
                .findFirst()
                .map(List::of)
                .orElse(List.of());
    }
}