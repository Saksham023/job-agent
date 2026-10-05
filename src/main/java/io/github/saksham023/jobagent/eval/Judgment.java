package io.github.saksham023.jobagent.eval;

/**
 * The judge's answer for one (profile, job) pair. Spring AI's BeanOutputConverter turns this record into the JSON
 * schema the model must follow, and parses the model's JSON back into it, so the field names here are the
 * contract with the rubric (eval/judge-rubric.md, "Answer").
 */
public record Judgment(Verdict verdict, RoleFit roleFit, ExperienceFit experienceFit, StackFit stackFit,
                       String reason) {

    public enum Verdict { APPLY, MAYBE, NO }

    public enum RoleFit { FIT, STRETCH, MISMATCH }

    public enum ExperienceFit { FIT, STRETCH, MISMATCH, UNKNOWN }

    public enum StackFit { GOOD, ACCEPTABLE, STRETCH, MISMATCH }
}