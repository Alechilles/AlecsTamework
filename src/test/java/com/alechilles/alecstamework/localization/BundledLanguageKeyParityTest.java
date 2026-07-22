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
    private static final Set<String> SPANISH_COMMAND_ROSTER_KEYS = Set.of(
            "tamework.ui.linkedPanel.revive.title",
            "tamework.ui.linkedPanel.revive.subtitle",
            "tamework.ui.linkedPanel.revive.costHeader",
            "tamework.ui.linkedPanel.revive.shortage",
            "tamework.ui.linkedPanel.revive.ready",
            "tamework.ui.linkedPanel.revive.missingComponents",
            "tamework.ui.linkedPanel.revive.confirm",
            "tamework.ui.linkedPanel.roster.state",
            "tamework.ui.linkedPanel.roster.state.active",
            "tamework.ui.linkedPanel.roster.state.unloaded",
            "tamework.ui.linkedPanel.roster.state.restoring",
            "tamework.ui.linkedPanel.roster.state.storing",
            "tamework.ui.linkedPanel.roster.state.stored",
            "tamework.ui.linkedPanel.roster.state.dead",
            "tamework.ui.linkedPanel.roster.state.lost",
            "tamework.ui.linkedPanel.roster.remaining",
            "tamework.ui.linkedPanel.roster.duration",
            "tamework.ui.linkedPanel.roster.unlimited",
            "tamework.ui.linkedPanel.roster.cooldown",
            "tamework.ui.linkedPanel.roster.capacity",
            "tamework.ui.linkedPanel.roster.capacityUnlimited",
            "tamework.ui.linkedPanel.roster.capBlocked",
            "tamework.ui.linkedPanel.roster.summon",
            "tamework.ui.linkedPanel.roster.dismiss"
    );

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
                if ("es-ES".equals(language) && !keys.containsAll(SPANISH_COMMAND_ROSTER_KEYS)) {
                    HashSet<String> missing = new HashSet<>(SPANISH_COMMAND_ROSTER_KEYS);
                    missing.removeAll(keys);
                    for (String key : missing) {
                        issues.add(language + " missing required command-roster key: " + key);
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
