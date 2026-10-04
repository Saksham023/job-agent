package io.github.saksham023.jobagent.company;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Admin, read-only view of the company registry.
 * Local use only for now: there is no authentication on /admin.
 */
@RestController
@RequestMapping("/admin/companies")
public class CompanyController {

    private final CompanyRepository companyRepository;

    public CompanyController(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @GetMapping
    public List<Company> listCompanies() {
        return companyRepository.findAll();
    }

    @GetMapping("/{slug}")
    public Company getCompany(@PathVariable String slug) {
        return companyRepository.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown company: " + slug));
    }
}