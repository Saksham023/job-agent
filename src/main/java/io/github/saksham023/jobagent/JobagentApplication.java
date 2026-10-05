package io.github.saksham023.jobagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan          // binds @ConfigurationProperties records such as MatchingProperties
public class JobagentApplication {

	public static void main(String[] args) {
		SpringApplication.run(JobagentApplication.class, args);
	}

}
