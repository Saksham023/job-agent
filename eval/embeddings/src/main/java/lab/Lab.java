package lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saksham023.jobagent.requirements.DescriptionSections;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Embedding experiment. "embed": vector for every open job with each model (timed, cached to disk).
 * "rank <searchId>...": for each test search, the rankings (rules as the search orders them, rules by score,
 * each model alone, hybrids) written to rankings-<searchId>.json.
 */
public class Lab {
    static final Path MODELS = Path.of(System.getProperty("user.home"), ".cache/job-agent/models");
    static final Path OUT = Path.of(System.getProperty("lab.out", "."));
    static final ObjectMapper JSON = new ObjectMapper();

    static List<Embedder> models() throws Exception {
        return List.of(
                new Embedder("minilm", MODELS.resolve("all-MiniLM-L6-v2"), 256, false, ""),
                new Embedder("bge", MODELS.resolve("bge-small-en-v1.5"), 512, true,
                        "Represent this sentence for searching relevant passages: "));
    }

    static Connection db() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://localhost:5433/jobagent", "postgres", "ragpass");
    }

    /** The job as the vector sees it: title, then the posting without its intro sections (company blurb, perks). */
    static String jobText(String title, String description) {
        StringBuilder text = new StringBuilder(title).append('\n');
        if (description != null) {
            for (DescriptionSections.Line line : DescriptionSections.lines(description)) {
                if (line.section() != DescriptionSections.Kind.INTRO) text.append(line.text()).append('\n');
                if (text.length() > 2500) break;
            }
        }
        return text.toString();
    }

    static String profileText(JsonNode judge) {
        List<String> skills = new ArrayList<>();
        judge.path("mainLanguages").forEach(s -> skills.add(s.asText()));
        judge.path("otherSkills").forEach(s -> skills.add(s.asText()));
        String years = judge.path("years").isNull() ? "unknown" : judge.path("years").asText();
        return "Looking for: " + judge.path("wants").asText() + "\nExperience: " + years + " years.\nSkills: "
                + String.join(", ", skills);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("embed")) embed();
        else rank(Arrays.copyOfRange(args, 1, args.length));
    }

    static void embed() throws Exception {
        Map<Long, String> texts = new LinkedHashMap<>();
        try (Connection c = db(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, title, description FROM jobs WHERE closed_at IS NULL ORDER BY id")) {
            while (rs.next()) texts.put(rs.getLong(1), jobText(rs.getString(2), rs.getString(3)));
        }
        for (Embedder m : models()) {
            long start = System.nanoTime();
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(OUT.resolve("emb-" + m.name + ".bin"))))) {
                out.writeInt(texts.size());
                for (var e : texts.entrySet()) {
                    float[] v = m.embed(e.getValue());
                    out.writeLong(e.getKey());
                    out.writeInt(v.length);
                    for (float x : v) out.writeFloat(x);
                }
            }
            double secs = (System.nanoTime() - start) / 1e9;
            System.out.printf("%s: %d jobs in %.1f s (%.1f ms per job)%n", m.name, texts.size(), secs, secs * 1000 / texts.size());
            m.close();
        }
    }

    static Map<Long, float[]> load(String model) throws IOException {
        Map<Long, float[]> vectors = new HashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(OUT.resolve("emb-" + model + ".bin"))))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long id = in.readLong();
                float[] v = new float[in.readInt()];
                for (int d = 0; d < v.length; d++) v[d] = in.readFloat();
                vectors.put(id, v);
            }
        }
        return vectors;
    }

    static void rank(String[] searchIds) throws Exception {
        List<Embedder> models = models();
        Map<String, Map<Long, float[]>> vectors = new HashMap<>();
        for (Embedder m : models) vectors.put(m.name, load(m.name));
        try (Connection c = db()) {
            for (String searchId : searchIds) {
                PreparedStatement ps = c.prepareStatement(
                        "SELECT profile::text, candidate_ids, candidate_scores, low_priority_from FROM searches WHERE id = ?::uuid");
                ps.setString(1, searchId);
                ResultSet rs = ps.executeQuery();
                rs.next();
                JsonNode judge = JSON.readTree(rs.getString(1)).path("judge");
                Long[] ids = (Long[]) rs.getArray(2).getArray();
                Integer[] scores = (Integer[]) rs.getArray(3).getArray();
                String query = profileText(judge);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("searchId", searchId);
                out.put("profileText", query);
                out.put("ids", ids);
                out.put("ruleScores", scores);
                out.put("lowPriorityFrom", rs.getObject(4));
                Map<String, double[]> cos = new LinkedHashMap<>();
                for (Embedder m : models) {
                    float[] q = m.embed(m.queryPrefix + query);
                    double[] sims = new double[ids.length];
                    for (int i = 0; i < ids.length; i++) {
                        float[] v = vectors.get(m.name).get(ids[i]);
                        sims[i] = v == null ? 0 : Embedder.cosine(q, v);
                    }
                    cos.put(m.name, sims);
                }
                out.put("cosine", cos);
                JSON.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve("rankings-" + searchId + ".json").toFile(), out);
                System.out.println(searchId + ": " + ids.length + " candidates ranked");
            }
        }
    }
}
