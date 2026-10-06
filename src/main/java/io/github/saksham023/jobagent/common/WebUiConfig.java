package io.github.saksham023.jobagent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the built web UI (the folder `npm --prefix web run build` creates, web/dist) from the same server as the API,
 * so one address shows the UI and the UI's calls to /api/v1 stay on the same origin (no CORS needed).
 *
 * Only when jobagent.web.dir (env JOBAGENT_WEB_DIR) points to that folder; blank = no UI served (development uses
 * the Vite dev server). Controllers win over these files, because a resource handler is the last resort for a URL.
 * The UI is one page (its filters live in the query string), so only "/" needs to map to index.html.
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebUiConfig.class);

    private final Path dir;

    public WebUiConfig(@Value("${jobagent.web.dir:}") String dir) {
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir.strip()).toAbsolutePath().normalize();
        if (this.dir != null && !Files.isRegularFile(this.dir.resolve("index.html"))) {
            log.warn("jobagent.web.dir {} has no index.html: run `npm --prefix web run build` and copy web/dist there", this.dir);
        } else if (this.dir != null) {
            log.info("Serving the web UI from {}", this.dir);
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (dir != null) {
            registry.addResourceHandler("/**").addResourceLocations(dir.toUri().toString());
        }
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (dir != null) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }
}
