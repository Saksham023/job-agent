package io.github.saksham023.jobagent.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * jobagent.account.*: a signed-in user's resume and profile.
 *
 * @param resumeModel   the model that reads the resume text into facts ("sonnet")
 * @param maxPdfBytes   largest resume PDF accepted (upload or Google Drive download)
 * @param maxPdfPages   only this many pages are read (a resume has 1-3)
 * @param fetchTimeout  how long a Google Drive download may take
 * @param readsPerHour  resume reads (model calls) one user may start per hour
 */
@ConfigurationProperties("jobagent.account")
public record AccountProperties(
        @DefaultValue("sonnet") String resumeModel,
        @DefaultValue("5000000") int maxPdfBytes,
        @DefaultValue("10") int maxPdfPages,
        @DefaultValue("PT20S") Duration fetchTimeout,
        @DefaultValue("5") int readsPerHour) {
}
