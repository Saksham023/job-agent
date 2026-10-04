package io.github.saksham023.jobagent.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;

/**
 * In-memory dictionary of places: countries (JDK + geo/country-aliases.csv), subdivisions (geo/subdivisions.csv),
 * metros (geo/metros.csv) and cities (geo/cities.csv). Loaded once at startup; read-only afterwards.
 * Answers "what is this exact name?". Interpreting full location strings is LocationParser's job.
 */
@Component
public class Gazetteer {

    private static final Logger log = LoggerFactory.getLogger(Gazetteer.class);

    public record Country(String code, String name) {}

    public record Subdivision(String countryCode, String name, String code) {}

    public record Metro(String countryCode, String key, String name) {}

    public record City(String countryCode, String region, String name, int priority, String metroKey) {}

    private final Map<String, Country> countriesByCode = new HashMap<>();          // "IN" -> India
    private final Map<String, Country> countriesByName = new HashMap<>();          // "india", "ind", "bharat" -> India
    private final Map<String, List<Subdivision>> subdivisionsByName = new HashMap<>(); // "karnataka" -> [Karnataka]
    private final Map<String, Subdivision> subdivisionsByCode = new HashMap<>();   // "IN:KA" -> Karnataka
    private final Map<String, Metro> metrosByKey = new HashMap<>();                // "NCR" -> Delhi NCR
    private final Map<String, Metro> metrosByName = new HashMap<>();               // "delhi ncr", "ncr" -> Delhi NCR
    private final Map<String, List<City>> citiesByName = new HashMap<>();          // "bangalore" -> [Bengaluru]
    private final Map<String, List<City>> citiesByMetro = new HashMap<>();         // "NCR" -> [Delhi, Noida, ...]

    public Gazetteer() {
        loadCountries();
        loadSubdivisions();
        loadMetros();
        loadCities();
        citiesByName.replaceAll((name, cities) -> cities.stream()
                .sorted(Comparator.comparingInt(City::priority).reversed())
                .toList());
        citiesByMetro.replaceAll((metro, cities) -> List.copyOf(cities));
        log.info("Gazetteer loaded: {} countries, {} subdivisions, {} metros, {} city names",
                countriesByCode.size(), subdivisionsByCode.size(), metrosByKey.size(), citiesByName.size());
    }

    // ---------------------------------------------------------------- lookups

    public Optional<Country> countryByCode(String iso2) {
        return iso2 == null ? Optional.empty() : Optional.ofNullable(countriesByCode.get(iso2.toUpperCase(Locale.ROOT)));
    }

    /** Full names, ISO alpha-3 codes and curated aliases: "India", "IND", "Bharat". Not generic ISO alpha-2. */
    public Optional<Country> countryByName(String text) {
        return Optional.ofNullable(countriesByName.get(key(text)));
    }

    /** Names and long aliases only. May return several (same name in different countries). */
    public List<Subdivision> subdivisionsByName(String text) {
        return subdivisionsByName.getOrDefault(key(text), List.of());
    }

    /** Short codes like "KA" or "TX"; only meaningful once the country is known. */
    public Optional<Subdivision> subdivisionByCode(String countryCode, String code) {
        return Optional.ofNullable(subdivisionsByCode.get(codeKey(countryCode, code)));
    }

    public Optional<Metro> metroByName(String text) {
        return Optional.ofNullable(metrosByName.get(key(text)));
    }

    /** Cities with this name or alias, highest priority first. */
    public List<City> citiesByName(String text) {
        return citiesByName.getOrDefault(key(text), List.of());
    }

    public List<City> citiesInMetro(String metroKey) {
        return citiesByMetro.getOrDefault(metroKey, List.of());
    }

    /** The normalization every lookup uses: no accents, single spaces, lowercase. "  Ôsaka " -> "osaka". */
    public static String key(String text) {
        if (text == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- loading (startup only)

    /** Reads the JDK country list, then geo/country-aliases.csv. */
    private void loadCountries() {
        for (String iso2 : Locale.getISOCountries()) {
            Locale locale = Locale.of("", iso2);
            Country country = new Country(iso2, locale.getDisplayCountry(Locale.ENGLISH));
            countriesByCode.put(iso2, country);
            countriesByName.put(key(country.name()), country);
            try {
                countriesByName.put(key(locale.getISO3Country()), country);
            } catch (MissingResourceException e) {
                // a few territories have no alpha-3 code; the name is enough
            }
        }

        String file = "geo/country-aliases.csv";                              // reads geo/country-aliases.csv
        for (String[] row : readCsv(file, 2)) {
            countriesByName.put(key(row[0]), requireCountry(row[1], file));
        }
    }

    /** Reads geo/subdivisions.csv. Short uppercase aliases (OR, TG) are stored as codes, not names. */
    private void loadSubdivisions() {
        String file = "geo/subdivisions.csv";                                 // reads geo/subdivisions.csv
        for (String[] row : readCsv(file, 4)) {
            Country country = requireCountry(row[0], file);
            Subdivision subdivision = new Subdivision(country.code(), row[1], row[2]);

            subdivisionsByCode.put(codeKey(country.code(), row[2]), subdivision);
            addTo(subdivisionsByName, row[1], subdivision);
            for (String alias : aliases(row[3])) {
                if (looksLikeCode(alias)) {
                    subdivisionsByCode.put(codeKey(country.code(), alias), subdivision);
                } else {
                    addTo(subdivisionsByName, alias, subdivision);
                }
            }
        }
    }

    /** Reads geo/metros.csv. */
    private void loadMetros() {
        String file = "geo/metros.csv";                                       // reads geo/metros.csv
        for (String[] row : readCsv(file, 4)) {
            Metro metro = new Metro(requireCountry(row[0], file).code(), row[1], row[2]);
            metrosByKey.put(metro.key(), metro);
            metrosByName.put(key(metro.key()), metro);
            metrosByName.put(key(metro.name()), metro);
            for (String alias : aliases(row[3])) {
                metrosByName.put(key(alias), metro);
            }
        }
    }

    /** Reads geo/cities.csv. Region and metro must already exist, so typos fail at startup. */
    private void loadCities() {
        String file = "geo/cities.csv";                                       // reads geo/cities.csv
        for (String[] row : readCsv(file, 6)) {
            Country country = requireCountry(row[0], file);
            String region = row[1].isEmpty() ? null : requireSubdivision(country.code(), row[1], file).name();
            int priority = Integer.parseInt(row[4]);
            String metroKey = row[5].isEmpty() ? null : requireMetro(row[5], file).key();

            City city = new City(country.code(), region, row[2], priority, metroKey);
            addTo(citiesByName, city.name(), city);
            for (String alias : aliases(row[3])) {
                addTo(citiesByName, alias, city);
            }
            if (metroKey != null) {
                addTo(citiesByMetro, metroKey, city);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Reads a classpath CSV: skips blank and # lines, requires exactly `columns` fields per row. */
    private static List<String[]> readCsv(String path, int columns) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] fields = trimmed.split(",", -1);
                if (fields.length != columns) {
                    throw new IllegalStateException(path + ":" + lineNumber + " expected " + columns
                            + " columns but found " + fields.length + ": " + line);
                }
                rows.add(Arrays.stream(fields).map(String::strip).toArray(String[]::new));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return rows;
    }

    private static List<String> aliases(String field) {
        return Arrays.stream(field.split("\\|"))
                .map(String::strip)
                .filter(alias -> !alias.isEmpty())
                .toList();
    }

    private static boolean looksLikeCode(String alias) {
        return alias.matches("[A-Z]{2,3}");
    }

    private static String codeKey(String countryCode, String code) {
        return (countryCode + ":" + code).toUpperCase(Locale.ROOT);
    }

    private static <T> void addTo(Map<String, List<T>> map, String name, T value) {
        map.computeIfAbsent(key(name), k -> new ArrayList<>()).add(value);
    }

    private Country requireCountry(String iso2, String file) {
        return countryByCode(iso2)
                .orElseThrow(() -> new IllegalStateException(file + ": unknown country code " + iso2));
    }

    private Subdivision requireSubdivision(String countryCode, String name, String file) {
        return subdivisionsByName(name).stream()
                .filter(s -> s.countryCode().equals(countryCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(file + ": unknown region " + name + " in " + countryCode));
    }

    private Metro requireMetro(String metroKey, String file) {
        Metro metro = metrosByKey.get(metroKey);
        if (metro == null) {
            throw new IllegalStateException(file + ": unknown metro " + metroKey);
        }
        return metro;
    }
}