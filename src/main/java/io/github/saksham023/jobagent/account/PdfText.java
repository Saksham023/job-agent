package io.github.saksham023.jobagent.account;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** The plain text of a resume PDF (Apache PDFBox). Only text goes on to the model: never the file itself. */
@Component
public class PdfText {

    static final int MAX_CHARS = 30_000;

    private final AccountProperties properties;

    public PdfText(AccountProperties properties) {
        this.properties = properties;
    }

    public String read(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (document.isEncrypted()) {
                throw new AccountException(HttpStatus.BAD_REQUEST, "The PDF is password-protected. Please use an unprotected copy.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(document.getNumberOfPages(), properties.maxPdfPages()));
            String text = stripper.getText(document).strip();
            if (text.length() < 100) {
                throw new AccountException(HttpStatus.BAD_REQUEST,
                        "We couldn't find text in this PDF (it may be a scanned image). Please use a PDF exported from a document editor.");
            }
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (IOException e) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "That file isn't a readable PDF.");
        }
    }
}
