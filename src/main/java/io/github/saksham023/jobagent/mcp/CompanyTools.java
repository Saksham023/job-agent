package io.github.saksham023.jobagent.mcp;

import io.github.saksham023.jobagent.company.CompanyRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP tools about the company registry. Spring AI finds @McpTool methods on Spring beans at startup and
 * publishes each one to MCP clients (Claude) with its name, description and a JSON schema of its inputs.
 */
@Component
public class CompanyTools {

    /** What Claude sees per company: no internal ids or platform config. */
    public record CompanySummary(String slug, String name, String platform, boolean crawled) {
    }

    private final CompanyRepository companyRepository;

    public CompanyTools(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @McpTool(
            name = "list_companies",
            description = "Lists the companies whose careers sites this server crawls for jobs in India, with the "
                    + "careers platform each one uses. Use it to tell the user which companies are covered.",
            annotations = @McpTool.McpAnnotations(title = "List companies", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public List<CompanySummary> listCompanies() {
        return companyRepository.findAll().stream()
                .map(c -> new CompanySummary(c.slug(), c.name(), c.platform(), c.enabled()))
                .toList();
    }
}