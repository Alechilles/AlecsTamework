package com.alechilles.alecstamework.integration.tooltips;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import java.util.Map;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.herolias.tooltips.api.TooltipData;
import org.herolias.tooltips.api.TooltipPriority;
import org.herolias.tooltips.api.TooltipProvider;

final class TameworkSpawnerTooltipProvider implements TooltipProvider {
    static final String PROVIDER_ID = "Alecstamework.Spawner";
    private static final String DEFAULT_LANGUAGE = "en-US";
    private static final String NAME_LINE_PREFIX = "Name: ";
    private static final String ROLE_LINE_PREFIX = "Role: ";

    private final ItemFeatureRegistry itemFeatureRegistry;
    private final TranslationRegistry translationRegistry;

    TameworkSpawnerTooltipProvider(ItemFeatureRegistry itemFeatureRegistry, TranslationRegistry translationRegistry) {
        this.itemFeatureRegistry = itemFeatureRegistry;
        this.translationRegistry = translationRegistry;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public int getPriority() {
        return TooltipPriority.LATE;
    }

    @Override
    public TooltipData getTooltipData(String itemId, String metadata) {
        return getTooltipData(itemId, metadata, DEFAULT_LANGUAGE);
    }

    @Override
    public TooltipData getTooltipData(String itemId, String metadata, String language) {
        ItemFeatureConfig config = resolveConfig(itemId);
        if (config == null || !config.isSpawnerEnabled()) {
            return null;
        }
        BsonDocument metadataDoc = parseMetadata(metadata);
        if (metadataDoc == null || !isCaptured(metadataDoc)) {
            return null;
        }
        String roleId = readString(metadataDoc, TameworkMetadataKeys.CAPTURE_ROLE_ID);
        String roleDisplay = resolveRoleDisplay(roleId, normalizeLanguage(language));
        String displayName = firstNonBlank(
                readString(metadataDoc, TameworkMetadataKeys.CAPTURE_TOOLTIP_DISPLAY_NAME),
                readString(metadataDoc, TameworkMetadataKeys.NPC_NAME),
                roleDisplay
        );
        if (displayName == null || displayName.isBlank()) {
            return null;
        }

        ItemFeatureConfig.SpawnerTooltipMode mode = config.getSpawnerTooltipMode();
        if (mode == null) {
            mode = ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE;
        }

        TooltipData.Builder builder = TooltipData.builder()
                .nameOverride(displayName)
                .hashInput((mode.name()) + "|" + displayName + "|" + (roleDisplay == null ? "" : roleDisplay));
        appendLine(builder, mode, NAME_LINE_PREFIX + displayName);
        if (roleDisplay != null && !roleDisplay.isBlank()) {
            appendLine(builder, mode, ROLE_LINE_PREFIX + roleDisplay);
        }
        TooltipData data = builder.build();
        return data.isEmpty() ? null : data;
    }

    @Nullable
    private ItemFeatureConfig resolveConfig(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank() || itemFeatureRegistry == null) {
            return null;
        }
        ItemFeatureConfig direct = itemFeatureRegistry.get(itemId);
        if (direct != null) {
            return direct;
        }
        String canonicalRequested = canonicalItemId(itemId);
        if (canonicalRequested == null) {
            return null;
        }
        for (Map.Entry<String, ItemFeatureConfig> entry : itemFeatureRegistry.snapshot().entrySet()) {
            if (entry == null || entry.getValue() == null) {
                continue;
            }
            ItemFeatureConfig config = entry.getValue();
            String filledItemId = config.getSpawnerFilledItemId();
            if (filledItemId == null || filledItemId.isBlank()) {
                continue;
            }
            String canonicalFilled = canonicalItemId(filledItemId);
            if (canonicalFilled != null && canonicalFilled.equals(canonicalRequested)) {
                return config;
            }
        }
        return null;
    }

    @Nullable
    private static BsonDocument parseMetadata(@Nullable String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            return BsonDocument.parse(metadata);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isCaptured(BsonDocument metadataDoc) {
        BsonValue captured = metadataDoc.get(TameworkMetadataKeys.CAPTURED);
        return captured != null && captured.isBoolean() && captured.asBoolean().getValue();
    }

    @Nullable
    private static String readString(BsonDocument metadataDoc, String key) {
        if (metadataDoc == null || key == null || !metadataDoc.containsKey(key)) {
            return null;
        }
        BsonValue value = metadataDoc.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            String raw = value.asString().getValue();
            return raw == null || raw.isBlank() ? null : raw;
        }
        String raw = value.toString();
        return raw == null || raw.isBlank() ? null : raw;
    }

    private void appendLine(TooltipData.Builder builder,
                            ItemFeatureConfig.SpawnerTooltipMode mode,
                            @Nullable String line) {
        if (builder == null || line == null || line.isBlank()) {
            return;
        }
        if (mode == ItemFeatureConfig.SpawnerTooltipMode.REPLACE) {
            builder.addLineOverride(line);
        } else {
            builder.addLine(line);
        }
    }

    @Nullable
    private String resolveRoleDisplay(@Nullable String roleId, String language) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        I18nModule i18n = null;
        try {
            i18n = I18nModule.get();
        } catch (Throwable ignored) {
            // Unit tests and some startup windows may not have i18n initialized yet.
        }
        String translated = resolveI18nMessage(i18n, language, roleId);
        if (translated != null) {
            return translated;
        }
        String derivedKey = "npcRoles." + roleId + ".name";
        translated = resolveI18nMessage(i18n, language, derivedKey);
        if (translated != null) {
            return translated;
        }
        if (translationRegistry != null) {
            translated = translationRegistry.get(roleId);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            translated = translationRegistry.get(derivedKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return roleId;
    }

    @Nullable
    private static String resolveI18nMessage(@Nullable I18nModule i18n, String language, String key) {
        if (i18n == null || key == null || key.isBlank()) {
            return null;
        }
        String translated = i18n.getMessage(language, key);
        if (translated == null || translated.isBlank() || translated.equals(key)) {
            return null;
        }
        return translated;
    }

    private static String normalizeLanguage(@Nullable String language) {
        return language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
    }

    @Nullable
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static String canonicalItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String trimmed = itemId.startsWith("*") ? itemId.substring(1) : itemId;
        String normalized = ItemFeatureRegistry.normalizeStateItemId(trimmed);
        if (normalized == null) {
            return null;
        }
        return normalized.startsWith("*") ? normalized.substring(1) : normalized;
    }
}
