package io.github.saksham023.jobagent.account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

/**
 * Downloads a resume PDF from Google Drive. Google answers a file shared with "Anyone with the link" with the PDF; a
 * private file gets Google's sign-in page instead (HTML), which is reported to the user as "not shared". Size-capped
 * and time-limited; redirects are followed only because Google itself sends us to its download host.
 */
@Component
public class DriveFetcher {

    static final String NOT_SHARED = "We couldn't open your file. In Google Drive, set sharing to \"Anyone with the link\", then try again.";
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    private final AccountProperties properties;
    private final HttpClient http;

    public DriveFetcher(AccountProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(properties.fetchTimeout())
                .build();
    }

    /** The PDF bytes behind the link. */
    public byte[] fetch(DriveLink link) {
        HttpRequest request = HttpRequest.newBuilder(link.downloadUri())
                .timeout(properties.fetchTimeout())
                .header("User-Agent", "Mozilla/5.0 (JobRadar resume import)")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) {
                    throw new AccountException(HttpStatus.BAD_REQUEST, "That file doesn't exist on Google Drive. Please check the link.");
                }
                if (response.statusCode() != 200) {
                    throw new AccountException(HttpStatus.BAD_REQUEST, NOT_SHARED);
                }
                byte[] bytes = body.readNBytes(properties.maxPdfBytes() + 1);
                return checkedPdf(bytes, properties.maxPdfBytes());
            }
        } catch (IOException e) {
            throw new AccountException(HttpStatus.BAD_GATEWAY, "Google Drive did not answer, please try again in a moment.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AccountException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted, please try again.");
        }
    }

    /** The bytes if they are a PDF within the size limit; HTML (Google's sign-in page) means the file is private. */
    static byte[] checkedPdf(byte[] bytes, int maxBytes) {
        if (bytes.length > maxBytes) {
            throw new AccountException(HttpStatus.PAYLOAD_TOO_LARGE, "The resume is larger than " + (maxBytes / 1_000_000) + " MB.");
        }
        if (bytes.length < PDF_MAGIC.length || !Arrays.equals(Arrays.copyOf(bytes, PDF_MAGIC.length), PDF_MAGIC)) {
            throw new AccountException(HttpStatus.BAD_REQUEST, NOT_SHARED);
        }
        return bytes;
    }
}
