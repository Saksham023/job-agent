package io.github.saksham023.jobagent.profile;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Profile ids like "p-7k3x9q2m": "p-" and 8 random characters of Crockford's base32 alphabet (digits and lower-case
 * letters without i, l, o, u, so nothing is easy to misread). 40 random bits: short enough to type, impossible to
 * guess, and anyone holding an id can search with that profile, so ids must stay unguessable.
 */
final class ProfileIds {

    private static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final int LENGTH = 8;
    private static final Pattern FORMAT = Pattern.compile("p-[0-9a-hjkmnp-tv-z]{" + LENGTH + "}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProfileIds() {
    }

    static String next() {
        StringBuilder id = new StringBuilder("p-");
        for (int i = 0; i < LENGTH; i++) {
            id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    /** The id as stored (trimmed, lower case), or an error for anything that cannot be a profile id. */
    static String normalize(String value) {
        String id = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(id).matches()) {
            throw new IllegalArgumentException("profileId must look like p-7k3x9q2m, got: " + value);
        }
        return id;
    }
}
