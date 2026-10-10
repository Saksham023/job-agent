package io.github.saksham023.jobagent.requirements;

/**
 * Our own job taxonomy. Platforms have no shared one, so every job is mapped onto these values.
 * The group tells how technical a family is: TECH families are what a software engineer searches in,
 * TECH_ADJACENT ones (sales engineering, hardware engineering, product, design...) are separate so the user can opt
 * in to them: a software search does not fill up with ASIC jobs unless asked.
 */
public enum JobFamily {
    SOFTWARE_ENGINEERING(Group.TECH),
    DATA_ML(Group.TECH),
    INFRA_DEVOPS(Group.TECH),
    SECURITY(Group.TECH),
    QA(Group.TECH),
    ENG_MANAGEMENT(Group.TECH),
    SALES_ENGINEERING(Group.TECH_ADJACENT),
    HARDWARE_ENGINEERING(Group.TECH_ADJACENT),     // chips and boards (ASIC, verification, physical design)
    PRODUCT(Group.TECH_ADJACENT),
    DESIGN(Group.TECH_ADJACENT),
    PROGRAM_MANAGEMENT(Group.TECH_ADJACENT),
    ANALYTICS(Group.TECH_ADJACENT),
    QUANT(Group.TECH_ADJACENT),                    // quant research, trading and strats; quant developers stay SWE
    SALES(Group.BUSINESS),
    MARKETING(Group.BUSINESS),
    FINANCE(Group.BUSINESS),
    RISK_COMPLIANCE(Group.BUSINESS),
    HR(Group.BUSINESS),
    LEGAL(Group.BUSINESS),
    SUPPORT(Group.BUSINESS),
    OPERATIONS(Group.BUSINESS),
    UNCLASSIFIED(Group.BUSINESS);

    public enum Group {
        TECH,
        TECH_ADJACENT,
        BUSINESS
    }

    private final Group group;

    JobFamily(Group group) {
        this.group = group;
    }

    public Group group() {
        return group;
    }

    public boolean isTech() {
        return group == Group.TECH;
    }
}