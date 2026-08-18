package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.attachments.ResolvedAttachmentDisplay;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.hypixel.hytale.server.core.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Builds the colored summary, progression, and appearance sections for captured spawner tooltips.
 */
final class SpawnerTooltipPresentationService {
    private static final String DEFAULT_LANGUAGE = "en-US";
    private static final String WHITE = "#FFFFFF";
    private static final String FEMALE = "#FF8FBD";
    private static final String MALE = "#63A9FF";
    private static final String TRAITS_HEADER = "#F6C453";
    private static final String APPEARANCE_HEADER = "#74D7E8";
    private static final int POSITIVE_RED = 0x55;
    private static final int POSITIVE_GREEN = 0xD6;
    private static final int POSITIVE_BLUE = 0x6B;
    private static final int NEGATIVE_RED = 0xFF;
    private static final int NEGATIVE_GREEN = 0x5C;
    private static final int NEGATIVE_BLUE = 0x5C;

    private final TranslationRegistry translationRegistry;
    private final Function<String, TwTraitConfig> traitConfigById;
    private final Function<String, TwTraitConfig> traitConfigByRole;
    private final Function<String, TwLevelingConfig> levelingConfigById;
    private final Function<String, TwLevelingConfig> levelingConfigByRole;

    SpawnerTooltipPresentationService(@Nullable TranslationRegistry translationRegistry) {
        this(
                translationRegistry,
                TwTraitConfig::resolveById,
                TwTraitConfig::resolveForRole,
                TwLevelingConfig::resolveById,
                TwLevelingConfig::resolveForRole
        );
    }

    SpawnerTooltipPresentationService(@Nullable TranslationRegistry translationRegistry,
                                      Function<String, TwTraitConfig> traitConfigById,
                                      Function<String, TwTraitConfig> traitConfigByRole,
                                      Function<String, TwLevelingConfig> levelingConfigById,
                                      Function<String, TwLevelingConfig> levelingConfigByRole) {
        this.translationRegistry = translationRegistry;
        this.traitConfigById = traitConfigById;
        this.traitConfigByRole = traitConfigByRole;
        this.levelingConfigById = levelingConfigById;
        this.levelingConfigByRole = levelingConfigByRole;
    }

    @Nullable
    Message buildDescription(@Nullable Message baseDescription,
                             @Nullable ItemFeatureConfig.SpawnerTooltipMode mode,
                             @Nullable String displayName,
                             @Nullable String roleDisplay,
                             @Nullable String gender,
                             @Nullable String roleId,
                             BsonDocument metadata,
                             List<ResolvedAttachmentDisplay> attachments) {
        Message tameworkDescription = buildTameworkDescription(
                displayName,
                roleDisplay,
                gender,
                roleId,
                metadata,
                attachments
        );
        if (tameworkDescription == null) {
            return mode == ItemFeatureConfig.SpawnerTooltipMode.REPLACE ? null : baseDescription;
        }
        ItemFeatureConfig.SpawnerTooltipMode resolvedMode = mode == null
                ? ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE
                : mode;
        if (resolvedMode == ItemFeatureConfig.SpawnerTooltipMode.REPLACE || isBlank(baseDescription)) {
            return tameworkDescription;
        }
        return Message.join(baseDescription, Message.raw("\n\n"), tameworkDescription);
    }

    @Nullable
    private Message buildTameworkDescription(@Nullable String displayName,
                                             @Nullable String roleDisplay,
                                             @Nullable String gender,
                                             @Nullable String roleId,
                                             BsonDocument metadata,
                                             List<ResolvedAttachmentDisplay> attachments) {
        Message summary = buildSummary(displayName, roleDisplay, gender, roleId, metadata);
        if (summary == null) {
            return null;
        }
        List<Message> sections = new ArrayList<>();
        List<Message> traitLines = buildTraitLines(roleId, metadata);
        if (!traitLines.isEmpty()) {
            sections.add(buildSection("-- Traits --", TRAITS_HEADER, traitLines));
        }
        List<Message> appearanceLines = buildAppearanceLines(attachments);
        if (!appearanceLines.isEmpty()) {
            sections.add(buildSection("-- Appearance --", APPEARANCE_HEADER, appearanceLines));
        }
        if (sections.isEmpty()) {
            return summary;
        }
        List<Message> parts = new ArrayList<>();
        parts.add(summary);
        for (Message section : sections) {
            parts.add(Message.raw("\n\n"));
            parts.add(section);
        }
        return join(parts);
    }

    @Nullable
    private Message buildSummary(@Nullable String displayName,
                                 @Nullable String roleDisplay,
                                 @Nullable String gender,
                                 @Nullable String roleId,
                                 BsonDocument metadata) {
        String resolvedDisplayName = normalize(displayName);
        String resolvedRoleDisplay = normalize(roleDisplay);
        if (resolvedDisplayName == null && resolvedRoleDisplay == null) {
            return null;
        }
        List<Message> parts = new ArrayList<>();
        if (resolvedDisplayName != null) {
            parts.add(white(resolvedDisplayName));
            if (resolvedRoleDisplay != null && !resolvedDisplayName.equalsIgnoreCase(resolvedRoleDisplay)) {
                parts.add(white(" - " + resolvedRoleDisplay));
            }
        } else {
            parts.add(white(resolvedRoleDisplay));
        }
        GenderDisplay genderDisplay = resolveGender(gender);
        if (genderDisplay != null) {
            parts.add(white(" ("));
            parts.add(Message.raw(genderDisplay.abbreviation()).color(genderDisplay.color()));
            parts.add(white(")"));
        }
        String level = resolveLevel(roleId, metadata);
        if (level != null) {
            parts.add(white(" - " + level));
        }
        return join(parts);
    }

    @Nullable
    private String resolveLevel(@Nullable String roleId, BsonDocument metadata) {
        Integer level = readInteger(metadata, TameworkMetadataKeys.LEVELING_LEVEL);
        if (level == null) {
            return null;
        }
        TwLevelingConfig config = resolveLevelingConfig(
                readString(metadata, TameworkMetadataKeys.LEVELING_CONFIG_ID),
                roleId
        );
        if (config == null) {
            return "Level " + Math.max(1, level);
        }
        int maxLevel = config.getLevels().getMaxLevel();
        return "Level " + Math.max(1, Math.min(level, maxLevel)) + "/" + maxLevel;
    }

    private List<Message> buildTraitLines(@Nullable String roleId, BsonDocument metadata) {
        String encoded = readString(metadata, TameworkMetadataKeys.TRAITS_VALUES);
        TameworkTraitsComponent.TraitValue[] values = TraitValueCodec.decode(encoded);
        if (values.length == 0) {
            return List.of();
        }
        TwTraitConfig config = resolveTraitConfig(
                readString(metadata, TameworkMetadataKeys.TRAITS_CONFIG_ID),
                roleId
        );
        List<Message> lines = new ArrayList<>(values.length);
        for (TameworkTraitsComponent.TraitValue value : values) {
            if (value == null || value.getId() == null || value.getId().isBlank()
                    || !Double.isFinite(value.getValue())) {
                continue;
            }
            TwTraitConfig.TraitDefinition definition = findDefinition(config, value.getId());
            lines.add(buildTraitLine(value, definition));
        }
        return lines;
    }

    private Message buildTraitLine(TameworkTraitsComponent.TraitValue traitValue,
                                   @Nullable TwTraitConfig.TraitDefinition definition) {
        String label = resolveTraitLabel(traitValue.getId(), definition);
        String formattedValue = formatDecimal(traitValue.getValue());
        if (definition == null) {
            return white(label + ": " + formattedValue);
        }
        double min = Math.min(definition.getBreedingMin(), definition.getBreedingMax());
        double max = Math.max(definition.getBreedingMin(), definition.getBreedingMax());
        double defaultValue = clamp(definition.getDefaultValue(), min, max);
        double ratio = relativeRatio(traitValue.getValue(), min, defaultValue, max);
        boolean negative = traitValue.getValue() < defaultValue;
        int percent = (int) Math.round(ratio * 100.0) * (negative ? -1 : 1);
        String valueColor = gradientColor(ratio, negative);
        return Message.join(
                white(label + ": "),
                Message.raw(formattedValue).color(valueColor),
                white("/" + formatDecimal(max) + " ("),
                Message.raw(formatPercent(percent)).color(valueColor),
                white(")")
        );
    }

    private List<Message> buildAppearanceLines(List<ResolvedAttachmentDisplay> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<Message> lines = new ArrayList<>(attachments.size());
        for (ResolvedAttachmentDisplay display : attachments) {
            if (display == null) {
                continue;
            }
            String line = normalize(display.toTooltipLine());
            if (line != null) {
                lines.add(white(line));
            }
        }
        return lines;
    }

    private static Message buildSection(String header, String headerColor, List<Message> lines) {
        List<Message> parts = new ArrayList<>(lines.size() * 2 + 1);
        parts.add(Message.raw(header).color(headerColor));
        for (Message line : lines) {
            parts.add(Message.raw("\n"));
            parts.add(line);
        }
        return join(parts);
    }

    @Nullable
    private TwTraitConfig resolveTraitConfig(@Nullable String configId, @Nullable String roleId) {
        TwTraitConfig config = apply(traitConfigById, configId);
        return config != null ? config : apply(traitConfigByRole, roleId);
    }

    @Nullable
    private TwLevelingConfig resolveLevelingConfig(@Nullable String configId, @Nullable String roleId) {
        TwLevelingConfig config = apply(levelingConfigById, configId);
        return config != null ? config : apply(levelingConfigByRole, roleId);
    }

    @Nullable
    private static <T> T apply(@Nullable Function<String, T> resolver, @Nullable String key) {
        return resolver == null || key == null ? null : resolver.apply(key);
    }

    @Nullable
    private static TwTraitConfig.TraitDefinition findDefinition(@Nullable TwTraitConfig config, String traitId) {
        if (config == null) {
            return null;
        }
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition != null && definition.getId() != null
                    && definition.getId().equalsIgnoreCase(traitId)) {
                return definition;
            }
        }
        return null;
    }

    private String resolveTraitLabel(String traitId, @Nullable TwTraitConfig.TraitDefinition definition) {
        String fallback = prettifyId(definition != null ? definition.getId() : traitId);
        if (definition == null) {
            return fallback;
        }
        String configured = normalize(definition.getDisplayName());
        if (configured != null && translationRegistry != null) {
            String translated = translationRegistry.get(configured);
            if (translated != null && !translated.isBlank() && !translated.equals(configured)) {
                return translated;
            }
        }
        return LocalizedText.resolveConfigValue(DEFAULT_LANGUAGE, configured, fallback);
    }

    @Nullable
    private static GenderDisplay resolveGender(@Nullable String gender) {
        String normalized = normalize(gender);
        if (normalized == null) {
            return null;
        }
        if ("female".equalsIgnoreCase(normalized) || "f".equalsIgnoreCase(normalized)) {
            return new GenderDisplay("F", FEMALE);
        }
        if ("male".equalsIgnoreCase(normalized) || "m".equalsIgnoreCase(normalized)) {
            return new GenderDisplay("M", MALE);
        }
        return null;
    }

    private static double relativeRatio(double value, double min, double defaultValue, double max) {
        if (value < defaultValue) {
            double distance = defaultValue - min;
            return distance <= 0.0 ? 0.0 : clamp((defaultValue - value) / distance, 0.0, 1.0);
        }
        double distance = max - defaultValue;
        return distance <= 0.0 ? 0.0 : clamp((value - defaultValue) / distance, 0.0, 1.0);
    }

    private static String gradientColor(double ratio, boolean negative) {
        double clampedRatio = clamp(ratio, 0.0, 1.0);
        int red = negative ? NEGATIVE_RED : POSITIVE_RED;
        int green = negative ? NEGATIVE_GREEN : POSITIVE_GREEN;
        int blue = negative ? NEGATIVE_BLUE : POSITIVE_BLUE;
        return String.format(
                Locale.ROOT,
                "#%02X%02X%02X",
                interpolate(0xFF, red, clampedRatio),
                interpolate(0xFF, green, clampedRatio),
                interpolate(0xFF, blue, clampedRatio)
        );
    }

    private static int interpolate(int start, int end, double ratio) {
        return (int) Math.round(start + (end - start) * ratio);
    }

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatPercent(int percent) {
        if (percent > 0) {
            return "+" + percent + "%";
        }
        return percent + "%";
    }

    private static String prettifyId(String id) {
        String normalized = id == null ? "Trait" : id.trim();
        int colon = normalized.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < normalized.length()) {
            normalized = normalized.substring(colon + 1);
        }
        if (normalized.regionMatches(true, 0, "Trait_", 0, 6)) {
            normalized = normalized.substring(6);
        }
        return normalized.replace('_', ' ').trim();
    }

    @Nullable
    private static String readString(BsonDocument metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        BsonValue value = metadata.get(key);
        if (value == null || value.isNull() || !value.isString()) {
            return null;
        }
        return normalize(value.asString().getValue());
    }

    @Nullable
    private static Integer readInteger(BsonDocument metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        BsonValue value = metadata.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt32()) {
            return value.asInt32().getValue();
        }
        if (value.isInt64()) {
            long raw = value.asInt64().getValue();
            return raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE ? null : (int) raw;
        }
        return null;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Message white(String text) {
        return Message.raw(text).color(WHITE);
    }

    private static Message join(List<Message> parts) {
        return Message.join(parts.toArray(new Message[0]));
    }

    private static boolean isBlank(@Nullable Message message) {
        if (message == null) {
            return true;
        }
        if ((message.getRawText() != null && !message.getRawText().isBlank())
                || (message.getMessageId() != null && !message.getMessageId().isBlank())) {
            return false;
        }
        for (Message child : message.getChildren()) {
            if (!isBlank(child)) {
                return false;
            }
        }
        return true;
    }

    private record GenderDisplay(String abbreviation, String color) {
    }
}
