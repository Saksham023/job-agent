package io.github.saksham023.jobagent.account;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Google Drive or Google Docs link to a resume, reduced to its file id. The server only ever downloads from addresses
 * it builds itself from that id, never from what the user typed, so a pasted link cannot make our server fetch some
 * other site (server-side request forgery).
 *
 * Accepted: drive.google.com/file/d/ID/..., drive.google.com/open?id=ID, drive.google.com/uc?id=ID,
 * docs.google.com/document/d/ID/... (a Google Doc, exported as PDF).
 */
public record DriveLink(String fileId, boolean googleDoc) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{10,200}");
    private static final Pattern FILE_PATH = Pattern.compile("^/file/d/([A-Za-z0-9_-]+)");
    private static final Pattern DOC_PATH = Pattern.compile("^/document/d/([A-Za-z0-9_-]+)");
    private static final Pattern ID_PARAM = Pattern.compile("(?:^|&)id=([A-Za-z0-9_-]+)");

    /** The link's file id, or empty when it is not a Google Drive / Docs link we accept. */
    public static Optional<DriveLink> parse(String link) {
        if (link == null || link.isBlank() || link.length() > 2000) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(link.strip());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase();
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        String id = null;
        boolean doc = false;
        if (host.equals("drive.google.com")) {
            Matcher m = FILE_PATH.matcher(path);
            if (m.find()) {
                id = m.group(1);
            } else if (path.equals("/open") || path.equals("/uc")) {
                Matcher q = ID_PARAM.matcher(query);
                id = q.find() ? q.group(1) : null;
            }
        } else if (host.equals("docs.google.com")) {
            Matcher m = DOC_PATH.matcher(path);
            if (m.find()) {
                id = m.group(1);
                doc = true;
            }
        }
        return id != null && ID.matcher(id).matches() ? Optional.of(new DriveLink(id, doc)) : Optional.empty();
    }

    /** Where the PDF is downloaded from, built from the id only. */
    public URI downloadUri() {
        return URI.create(googleDoc
                ? "https://docs.google.com/document/d/" + fileId + "/export?format=pdf"
                : "https://drive.google.com/uc?export=download&id=" + fileId);
    }
}
