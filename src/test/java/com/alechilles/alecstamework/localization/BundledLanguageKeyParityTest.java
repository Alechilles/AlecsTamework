package com.alechilles.alecstamework.localization;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledLanguageKeyParityTest {
    private static final Set<String> PARTIAL_LOCALES = Set.of("es-ES");
    @Test
    void bundledServerLangFilesExposeKnownKeysAndCompleteLocalesMatchEnglish() throws Exception {
        Path root = Path.of("src/main/resources/Server/Languages");
        Set<String> englishKeys = loadKeys(root.resolve("en-US/server.lang"));
        HashSet<String> issues = new HashSet<>();

        try (Stream<Path> directories = Files.list(root)) {
            for (Path languageDir : directories.filter(Files::isDirectory).toList()) {
                String language = languageDir.getFileName().toString();
                if ("en-US".equals(language)) {
                    continue;
                }
                Path langFile = languageDir.resolve("server.lang");
                Set<String> keys = loadKeys(langFile);
                if (!PARTIAL_LOCALES.contains(language)) {
                    for (String key : englishKeys) {
                        if (!keys.contains(key)) {
                            issues.add(language + " missing key: " + key);
                        }
                    }
                }
                for (String key : keys) {
                    if (!englishKeys.contains(key)) {
                        issues.add(language + " extra key: " + key);
                    }
                }
            }
        }

        assertTrue(issues.isEmpty(), String.join("\n", issues.stream().sorted().toList()));
    }

    private static Set<String> loadKeys(Path path) throws Exception {
        HashSet<String> keys = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || line.startsWith("#")) {
                continue;
            }
            keys.add(line.substring(0, separator));
        }
        return keys;
    }
}
