package io.github.saksham023.jobagent.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Where the evaluation files live (jobagent.eval.dir, default "eval" = the repo's eval/ folder when the app runs
 * from the project root): judge-rubric.md, judge-profiles.json, labels.csv and the runs/ the judge writes.
 */
@ConfigurationProperties("jobagent.eval")
public record EvalProperties(@DefaultValue("eval") String dir) {

    public Path path(String first, String... more) {
        return Path.of(dir).resolve(Path.of(first, more));
    }
}