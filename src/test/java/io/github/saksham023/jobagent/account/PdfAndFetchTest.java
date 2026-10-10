package io.github.saksham023.jobagent.account;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfAndFetchTest {

    private static final AccountProperties PROPS = new AccountProperties("sonnet", 5_000_000, 10, Duration.ofSeconds(20), 5);

    private static byte[] pdf(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream text = new PDPageContentStream(doc, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                text.newLineAtOffset(50, 700);
                for (String line : lines) {
                    text.showText(line);
                    text.newLineAtOffset(0, -14);
                }
                text.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void readsTheTextOfAPdf() throws IOException {
        byte[] resume = pdf("Jane Doe, Software Engineer", "Experience: Acme Corp, Aug 2024 - present, Java, Spring Boot, Kafka",
                "Skills: Java, Python, PostgreSQL, Redis, AWS, Docker, Kubernetes");
        String text = new PdfText(PROPS).read(DriveFetcher.checkedPdf(resume, PROPS.maxPdfBytes()));
        assertThat(text).contains("Software Engineer", "Spring Boot", "PostgreSQL");
    }

    @Test
    void anAlmostEmptyPdfLooksLikeAScanAndIsRefused() throws IOException {
        assertThatThrownBy(() -> new PdfText(PROPS).read(pdf("Hi"))).hasMessageContaining("scanned image");
    }

    @Test
    void googlesSignInPageInsteadOfAPdfMeansTheFileIsNotShared() {
        byte[] html = "<!doctype html><html><title>Sign in - Google Accounts</title>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> DriveFetcher.checkedPdf(html, PROPS.maxPdfBytes())).hasMessage(DriveFetcher.NOT_SHARED);
        assertThatThrownBy(() -> new PdfText(PROPS).read("%PDF-1.7 garbage".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("readable PDF");
    }

    @Test
    void tooLargeIsRefusedWith413() {
        assertThatThrownBy(() -> DriveFetcher.checkedPdf(new byte[11], 10))
                .satisfies(e -> assertThat(((AccountException) e).status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void theAnswerSchemaAllowsUnknownYears() {
        String schema = ResumeReader.yearsMayBeNull(new org.springframework.ai.converter.BeanOutputConverter<>(ResumeReader.Answer.class)
                .getJsonSchema(), JsonMapper.builder().build());
        assertThat(schema).contains("\"years\":{\"type\":[\"number\",\"null\"]");
    }
}
