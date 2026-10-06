package io.github.saksham023.jobagent.skills;

import io.github.saksham023.jobagent.requirements.SkillExtractor.Category;
import io.github.saksham023.jobagent.requirements.SkillExtractor.LearnedSkill;
import io.github.saksham023.jobagent.skills.SkillMiner.Candidate;
import io.github.saksham023.jobagent.skills.SkillReviewer.Outcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** skill_candidates and learned_skills, plus the job lookups the learning needs. */
@Repository
public class SkillLearningRepository {

    /** A candidate as stored, with its decision. */
    public record StoredCandidate(String key, String term, int jobs, int companies, List<String> contexts, String status,
                                  String skill, String category, List<String> spellings, boolean ambiguous,
                                  String reason, boolean applied) {

        Candidate candidate() {
            return new Candidate(key, term, jobs, companies, contexts);
        }
    }

    /** One open job's text, for mining. */
    public record JobText(long id, long companyId, String title, String description) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public SkillLearningRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public List<JobText> openJobs() {
        return jdbc.sql("SELECT id, company_id, title, description FROM jobs WHERE closed_at IS NULL ORDER BY id")
                .query((rs, n) -> new JobText(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4))).list();
    }

    public List<String> companyNames() {
        return jdbc.sql("SELECT name FROM companies").query(String.class).list();
    }

    /** Inserts new terms and refreshes the counts of known ones; returns how many were new. */
    public int upsert(List<Candidate> candidates) {
        int inserted = 0;
        for (Candidate c : candidates) {
            boolean isNew = jdbc.sql("""
                            INSERT INTO skill_candidates (term_key, term, jobs, companies, contexts)
                            VALUES (:key, :term, :jobs, :companies, CAST(:contexts AS jsonb))
                            ON CONFLICT (term_key) DO UPDATE SET term = EXCLUDED.term, jobs = EXCLUDED.jobs,
                                companies = EXCLUDED.companies, contexts = EXCLUDED.contexts, last_seen = now()
                            RETURNING (xmax = 0) AS inserted
                            """)
                    .param("key", c.key()).param("term", c.term()).param("jobs", c.jobs())
                    .param("companies", c.companies()).param("contexts", jsonMapper.writeValueAsString(c.contexts()))
                    .query(Boolean.class).single();
            inserted += isNew ? 1 : 0;
        }
        return inserted;
    }

    public List<StoredCandidate> candidates(String status, int minCompanies, int limit) {
        return jdbc.sql("""
                        SELECT * FROM skill_candidates
                        WHERE (CAST(:status AS text) IS NULL OR status = :status) AND companies >= :min
                        ORDER BY companies DESC, jobs DESC, term_key LIMIT :limit
                        """)
                .param("status", status).param("min", minCompanies).param("limit", limit)
                .query(this::stored).list();
    }

    public void decide(String key, Outcome outcome, String model) {
        jdbc.sql("""
                        UPDATE skill_candidates SET status = :status, skill = :skill, category = :category,
                            spellings = :spellings, ambiguous = :ambiguous, reason = :reason, model = :model,
                            decided_at = now()
                        WHERE term_key = :key
                        """)
                .param("key", key).param("status", outcome.status()).param("skill", outcome.skill())
                .param("category", outcome.category() == null ? null : outcome.category().name())
                .param("spellings", outcome.spellings().toArray(String[]::new)).param("ambiguous", outcome.ambiguous())
                .param("reason", outcome.reason()).param("model", model)
                .update();
    }

    /** Accepted decisions not applied yet. */
    public List<StoredCandidate> toApply() {
        return jdbc.sql("SELECT * FROM skill_candidates WHERE status IN ('NEW_SKILL', 'ALIAS') AND applied_at IS NULL "
                        + "ORDER BY companies DESC")
                .query(this::stored).list();
    }

    public void learn(String skill, Category category, String alias, boolean ambiguous, String sourceKey) {
        jdbc.sql("""
                        INSERT INTO learned_skills (skill, category, alias, ambiguous, source_term)
                        VALUES (:skill, :category, :alias, :ambiguous, :source)
                        ON CONFLICT (skill, alias) DO UPDATE SET active = true, ambiguous = EXCLUDED.ambiguous
                        """)
                .param("skill", skill).param("category", category.name()).param("alias", alias)
                .param("ambiguous", ambiguous).param("source", sourceKey).update();
    }

    public void markApplied(String key) {
        jdbc.sql("UPDATE skill_candidates SET applied_at = now() WHERE term_key = :key").param("key", key).update();
    }

    public List<LearnedSkill> activeLearned() {
        return jdbc.sql("SELECT skill, category, alias, ambiguous FROM learned_skills WHERE active ORDER BY created_at, skill")
                .query((rs, n) -> new LearnedSkill(rs.getString(1), Category.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getBoolean(4)))
                .list();
    }

    /** Marks the open jobs whose title or text mentions any of these spellings for re-extraction; returns how many. */
    public int markJobsMentioning(List<String> spellings) {
        if (spellings.isEmpty()) {
            return 0;
        }
        // whole words only: "AI" must not match "maintain", ".NET" must not match "internet"
        String[] patterns = spellings.stream().map(SkillLearningRepository::wordPattern).toArray(String[]::new);
        return jdbc.sql("""
                        UPDATE job_requirements r SET extractor_version = 0
                        FROM jobs j
                        WHERE r.job_id = j.id AND j.closed_at IS NULL
                          AND (j.title ~* ANY(CAST(:patterns AS text[])) OR j.description ~* ANY(CAST(:patterns AS text[])))
                        """)
                .param("patterns", patterns).update();
    }

    /** A Postgres regex for the spelling as a whole word: no letter or digit right before or after it. */
    static String wordPattern(String spelling) {
        return "(^|[^[:alnum:]])" + spelling.replaceAll("[\\\\.^$|?*+()\\[\\]{}]", "\\\\$0") + "([^[:alnum:]]|$)";
    }

    /** Open jobs that ask for at least one of these skills (required or preferred). */
    public int jobsWithAnySkill(List<String> skills) {
        if (skills.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        SELECT count(*) FROM jobs j JOIN job_requirements r ON r.job_id = j.id
                        WHERE j.closed_at IS NULL AND (r.required_skills || r.preferred_skills) && CAST(:skills AS text[])
                        """)
                .param("skills", skills.toArray(String[]::new)).query(Integer.class).single();
    }

    private StoredCandidate stored(ResultSet rs, int rowNum) throws SQLException {
        Array spellings = rs.getArray("spellings");
        return new StoredCandidate(rs.getString("term_key"), rs.getString("term"), rs.getInt("jobs"),
                rs.getInt("companies"), jsonMapper.readValue(rs.getString("contexts"), new TypeReference<List<String>>() {
                }), rs.getString("status"), rs.getString("skill"), rs.getString("category"),
                spellings == null ? List.of() : Arrays.asList((String[]) spellings.getArray()), rs.getBoolean("ambiguous"),
                rs.getString("reason"), rs.getTimestamp("applied_at") != null);
    }
}
