package com.alechilles.alecstamework.localization;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInTameworkLanguageKeyCoverageTest {
    private static final Pattern LOCALIZED_FIELD = Pattern.compile(
            "\"(?:DisplayName|Description|RoleDisplayName|Branch|Message|HudMessage|ChatMessage|"
                    + "CommandFeedback|Tooltip|Label|Prompt|Title|Text)\"\\s*:\\s*\"([^\"]+)\""
    );
    private static final List<String> REQUIRED_LOCALES = List.of("en-US", "de-DE", "fr-FR", "fr-CA", "pt-BR");
    private static final List<Path> PLAYER_FACING_CONFIG_DIRS = List.of(
            Path.of("src/main/resources/Server/Tamework/Talents"),
            Path.of("src/main/resources/Server/Tamework/Traits"),
            Path.of("src/main/resources/Server/Tamework/Items/Commands"),
            Path.of("src/main/resources/Server/Tamework/Items/Naming"),
            Path.of("src/main/resources/Server/Tamework/Items/Spawners"),
            Path.of("src/main/resources/Server/Tamework/Interactions"),
            Path.of("src/main/resources/Server/Tamework/Happiness")
    );

    @Test
    void builtInTameworkDisplayFieldsUseBundledLanguageKeys() throws Exception {
        Set<String> configKeys = new HashSet<>();
        HashSet<String> missing = new HashSet<>();

        for (Path root : PLAYER_FACING_CONFIG_DIRS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Path path : Files.walk(root).filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                Matcher matcher = LOCALIZED_FIELD.matcher(content);
                while (matcher.find()) {
                    String value = matcher.group(1);
                    if (!isLanguageKey(value)) {
                        missing.add(path + " uses raw text: " + value);
                    } else {
                        configKeys.add(normalizeKey(value));
                    }
                }
            }
        }

        for (String locale : REQUIRED_LOCALES) {
            Set<String> localeKeys = loadKeys(Path.of("src/main/resources/Server/Languages/" + locale + "/server.lang"));
            for (String key : configKeys) {
                if (!localeKeys.contains(key)) {
                    missing.add(locale + " missing key: " + key);
                }
            }
        }

        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    private static boolean isLanguageKey(String value) {
        return value != null && value.matches("^(server\\.)?[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$");
    }

    private static String normalizeKey(String value) {
        return value.replaceFirst("^server\\.", "");
    }

    private static Set<String> loadKeys(Path path) throws Exception {
        HashSet<String> keys = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || line.startsWith("#")) {
                continue;
            }
            keys.add(normalizeKey(line.substring(0, separator)));
        }
        return keys;
    }
}
