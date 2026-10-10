package io.github.saksham023.jobagent.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DriveLinkTest {

    private static final String ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345";

    @Test
    void acceptsTheUsualDriveAndDocsLinks() {
        assertThat(DriveLink.parse("https://drive.google.com/file/d/" + ID + "/view?usp=sharing")).contains(new DriveLink(ID, false));
        assertThat(DriveLink.parse("https://drive.google.com/open?id=" + ID)).contains(new DriveLink(ID, false));
        assertThat(DriveLink.parse("https://drive.google.com/uc?export=download&id=" + ID)).contains(new DriveLink(ID, false));
        assertThat(DriveLink.parse("  https://docs.google.com/document/d/" + ID + "/edit  ")).contains(new DriveLink(ID, true));
    }

    @Test
    void theDownloadAddressIsBuiltFromTheIdOnly() {
        assertThat(new DriveLink(ID, false).downloadUri().toString()).isEqualTo("https://drive.google.com/uc?export=download&id=" + ID);
        assertThat(new DriveLink(ID, true).downloadUri().toString()).isEqualTo("https://docs.google.com/document/d/" + ID + "/export?format=pdf");
    }

    @Test
    void rejectsEverythingElseIncludingTricksToReachOtherHosts() {
        assertThat(DriveLink.parse("http://drive.google.com/file/d/" + ID + "/view")).isEmpty();          // not https
        assertThat(DriveLink.parse("https://drive.google.com.evil.com/file/d/" + ID)).isEmpty();
        assertThat(DriveLink.parse("https://evil.com/file/d/" + ID)).isEmpty();
        assertThat(DriveLink.parse("https://user@drive.google.com/file/d/" + ID)).isEmpty();             // user info
        assertThat(DriveLink.parse("https://drive.google.com:8443/file/d/" + ID)).isEmpty();             // odd port
        assertThat(DriveLink.parse("https://localhost/file/d/" + ID)).isEmpty();
        assertThat(DriveLink.parse("https://docs.google.com/spreadsheets/d/" + ID)).isEmpty();          // not a document
        assertThat(DriveLink.parse("https://drive.google.com/drive/folders/" + ID)).isEmpty();          // a folder
        assertThat(DriveLink.parse("https://drive.google.com/file/d/short/view")).isEmpty();
        assertThat(DriveLink.parse("not a link")).isEmpty();
        assertThat(DriveLink.parse(null)).isEmpty();
    }
}
