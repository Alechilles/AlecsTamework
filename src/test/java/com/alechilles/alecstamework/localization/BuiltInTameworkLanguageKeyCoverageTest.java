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
            "\"(?:DisplayName|Description|Branch|Message|HudMessage|ChatMessage|Label)\"\\s*:\\s*\"([^\"]+)\""
    );
    private static final List<Path> PLAYER_FACING_CONFIG_DIRS = List.of(
            Path.of("src/main/resources/Server/Tamework/Talents"),
            Path.of("src/main/resources/Server/Tamework/Traits"),
            Path.of("src/main/resources/Server/Tamework/Items/Commands"),
            Path.of("src/main/resources/Server/Tamework/Interactions"),
            Path.of("src/main/resources/Server/Tamework/Happiness")
    );

    @Test
    void builtInTameworkDisplayFieldsUseBundledLanguageKeys() throws Exception {
        Set<String> englishKeys = loadKeys(Path.of("src/main/resources/Server/Languages/en-US/server.lang"));
        Set<String> germanKeys = loadKeys(Path.of("src/main/resources/Server/Languages/de-DE/server.lang"));
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
                    if (!value.startsWith("tamework.")) {
                        missing.add(path + " uses raw text: " + value);
                    } else if (!englishKeys.contains(value)) {
                        missing.add(path + " missing en-US key: " + value);
                    } else if (!germanKeys.contains(value)) {
                        missing.add(path + " missing de-DE key: " + value);
                    }
                }
            }
        }

        assertTrue(missing.isEmpty(), String.join("\n", missing));
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
