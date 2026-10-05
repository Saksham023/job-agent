package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.ExperienceExtractor.Experience;
import io.github.saksham023.jobagent.requirements.JobClassifier.Classification;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Skills;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the jobs whose requirements must be (re)extracted and writes the results into job_requirements.
 */
@Repository
public class RequirementsRepository {

    /** Everything the extractors need for one job. */
    public record JobText(long jobId, String title, String department, String function, String description,
                          String employmentType, String companyName) {
    }

    /** What the extraction has produced so far, for the coverage report. */
    public record Coverage(long jobs, Map<String, Long> byFamily, Map<String, Long> byYearsConfidence,
                           long withRequiredSkills, long withPrimaryLanguage, Map<String, Long> topUnclassifiedTitles) {
    }

    /**
     * Open jobs that have no requirements yet, were extracted by an older extractor version, or whose
     * content changed after the last extraction.
     */
    private static final String SELECT_JOBS_TO_EXTRACT = """
            SELECT j.id, j.title, j.department, j.function,
                   j.description, j.employment_type, c.name AS company_name
            FROM jobs j
            JOIN companies c ON c.id = j.company_id
            LEFT JOIN job_requirements r ON r.job_id = j.id
            WHERE j.closed_at IS NULL
              AND (r.job_id IS NULL
                   OR r.extractor_version < :version
                   OR r.extracted_at < j.content_changed_at)
            ORDER BY j.id
            """;

    /** Every open posting of one company, for finding its boilerplate lines. */
    private static final String SELECT_COMPANY_POSTINGS = """
            SELECT j.title, j.description
            FROM jobs j
            JOIN companies c ON c.id = j.company_id
            WHERE j.closed_at IS NULL AND c.name = :company
            """;

    private static final String UPSERT = """
            INSERT INTO job_requirements (job_id, extractor_version,
                                          min_years, max_years, preferred_min_years, years_evidence, years_confidence,
                                          family, secondary_families, specialization, family_reasons, employment_type,
                                          required_skills, preferred_skills, primary_languages, extracted_at,
                                          years_source, family_source, languages_source, family_guessed)
            VALUES (:jobId, :version,
                    :minYears, :maxYears, :preferredMinYears, :yearsEvidence, :yearsConfidence,
                    :family, :secondaryFamilies, :specialization, CAST(:familyReasons AS jsonb), :employmentType,
                    :requiredSkills, :preferredSkills, :primaryLanguages, now(),
                    'rules', 'rules', 'rules', :familyGuessed)
            ON CONFLICT (job_id) DO UPDATE SET
                extractor_version   = EXCLUDED.extractor_version,
                min_years           = EXCLUDED.min_years,
                max_years           = EXCLUDED.max_years,
                preferred_min_years = EXCLUDED.preferred_min_years,
                years_evidence      = EXCLUDED.years_evidence,
                years_confidence    = EXCLUDED.years_confidence,
                family              = EXCLUDED.family,
                secondary_families  = EXCLUDED.secondary_families,
                specialization      = EXCLUDED.specialization,
                family_reasons      = EXCLUDED.family_reasons,
                employment_type     = EXCLUDED.employment_type,
                required_skills     = EXCLUDED.required_skills,
                preferred_skills    = EXCLUDED.preferred_skills,
                primary_languages   = EXCLUDED.primary_languages,
                extracted_at        = EXCLUDED.extracted_at,
                years_source        = EXCLUDED.years_source,
                family_source       = EXCLUDED.family_source,
                languages_source    = EXCLUDED.languages_source,
                family_guessed      = EXCLUDED.family_guessed
            """;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public RequirementsRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /** @param version the current extractor version; pass Integer.MAX_VALUE to select every open job */
    public List<JobText> findJobsToExtract(int version) {
        return jdbc.sql(SELECT_JOBS_TO_EXTRACT)
                .param("version", version)
                .query((rs, rowNum) -> new JobText(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("department"),
                        rs.getString("function"),
                        rs.getString("description"),
                        rs.getString("employment_type"),
                        rs.getString("company_name")))
                .list();
    }

    public List<CompanyBoilerplate.Posting> companyPostings(String companyName) {
        return jdbc.sql(SELECT_COMPANY_POSTINGS)
                .param("company", companyName)
                .query((rs, rowNum) -> new CompanyBoilerplate.Posting(rs.getString("title"), rs.getString("description")))
                .list();
    }

    public void upsert(long jobId, int version, Experience experience, Classification classification,
                       Skills skills, String employmentType) {
        jdbc.sql(UPSERT)
                .param("jobId", jobId)
                .param("version", version)
                .param("minYears", experience.minYears())
                .param("maxYears", experience.maxYears())
                .param("preferredMinYears", experience.preferredMinYears())
                .param("yearsEvidence", experience.evidence())
                .param("yearsConfidence", experience.confidence().name())
                .param("family", classification.family().name())
                .param("secondaryFamilies", classification.secondaryFamilies().stream().map(Enum::name).toArray(String[]::new))
                .param("specialization", classification.specialization() == null ? null : classification.specialization().name())
                .param("familyGuessed", classification.familyGuessed())
                .param("familyReasons", jsonMapper.writeValueAsString(classification.reasons()))
                .param("employmentType", employmentType)
                .param("requiredSkills", skills.required().toArray(String[]::new))
                .param("preferredSkills", skills.preferred().toArray(String[]::new))
                .param("primaryLanguages", skills.primaryLanguages().toArray(String[]::new))
                .update();
    }

    public Coverage coverage() {
        long jobs = jdbc.sql("SELECT count(*) FROM job_requirements").query(Long.class).single();
        long withSkills = jdbc.sql("SELECT count(*) FROM job_requirements WHERE cardinality(required_skills) > 0")
                .query(Long.class).single();
        long withPrimary = jdbc.sql("SELECT count(*) FROM job_requirements WHERE cardinality(primary_languages) > 0")
                .query(Long.class).single();
        return new Coverage(jobs,
                counts("SELECT family AS k, count(*) AS n FROM job_requirements GROUP BY family ORDER BY n DESC"),
                counts("SELECT years_confidence AS k, count(*) AS n FROM job_requirements GROUP BY years_confidence ORDER BY n DESC"),
                withSkills,
                withPrimary,
                counts("""
                        SELECT j.title AS k, count(*) AS n
                        FROM job_requirements r JOIN jobs j ON j.id = r.job_id
                        WHERE r.family = 'UNCLASSIFIED'
                        GROUP BY j.title ORDER BY n DESC, j.title LIMIT 20
                        """));
    }

    /** Runs a "SELECT k, n" query into an ordered map. */
    private Map<String, Long> counts(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql(sql).query(rs -> {
            result.put(rs.getString("k"), rs.getLong("n"));
        });
        return result;
    }
}