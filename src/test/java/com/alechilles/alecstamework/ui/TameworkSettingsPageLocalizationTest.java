package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsPageLocalizationTest {

    @Test
    void settingsPageDropdownsAndStatusUseLocalizedText() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolve(playerRef"));
        assertTrue(content.contains("LocalizedText.format(playerRef"));
        assertTrue(content.contains("TameworkSettingsPreset.dropdownEntries(resolveLanguage())"));
        assertFalse(content.contains("LocalizableString.fromString(\"Per World\")"));
        assertFalse(content.contains("\"Failed to apply settings.\""));
        assertFalse(content.contains("\"Applied settings.\""));
    }

    @Test
    void settingsAnnouncementButtonsUseLocalizedText() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsAnnouncementPage.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("tamework.ui.settingsAnnouncement.button.later"));
        assertTrue(content.contains("tamework.ui.settingsAnnouncement.button.review"));
        assertTrue(content.contains("#TwSettingsAnnouncementLaterButton.Text"));
        assertTrue(content.contains("#TwSettingsAnnouncementReviewButton.Text"));
        assertFalse(content.contains("Important Alec's Tamework Update"));
        assertFalse(content.contains("Don't show again until next announcement"));
    }

    @Test
    void settingsPageStaticTextBinderUsesBundledLanguageKeys() throws Exception {
        String page = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java"),
                StandardCharsets.UTF_8
        );
        String binder = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPageTextBinder.java"),
                StandardCharsets.UTF_8
        );
        String ui = Files.readString(
                Path.of("src/main/resources/Common/UI/Custom/TameworkSettingsPage.ui"),
                StandardCharsets.UTF_8
        );

        assertTrue(page.contains("TameworkSettingsPageTextBinder.bindStaticText(commandBuilder, playerRef)"));
        assertTrue(ui.contains("Label #TwSettingsExperiencePresetsLabel"));
        assertTrue(ui.contains("Label #TwSettingsTelemetryConsentInstructionLabel"));
        assertFalse(ui.contains("CheckBox #TwSettingsTelemetryEnabledCheck"));
        assertTrue(binder.contains("#TwSettingsTitle"));
        assertTrue(binder.contains("#TwSettingsExperiencePresetsTooltip"));
        assertTrue(binder.contains("#TwSettingsPresetDropdown"));

        LinkedHashSet<String> keys = extractLanguageKeys(binder);
        assertTrue(keys.size() > 100, "Expected broad settings page key coverage.");
        for (String locale : List.of("en-US", "de-DE", "fr-FR", "fr-CA", "pt-BR")) {
            String languageFile = Files.readString(
                    Path.of("src/main/resources/Server/Languages/" + locale + "/server.lang"),
                    StandardCharsets.UTF_8
            );
            for (String key : keys) {
                assertTrue(languageFile.contains(key + "="), () -> locale + " missing " + key);
            }
        }
    }

    private static LinkedHashSet<String> extractLanguageKeys(String source) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\"(tamework\\.ui\\.[^\"]+)\"").matcher(source);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
