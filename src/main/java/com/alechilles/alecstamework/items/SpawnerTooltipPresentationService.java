package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.api.ProgressionView;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.attachments.ResolvedAttachmentDisplay;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.alechilles.alecstamework.npc.progression.TraitPresentationViewMapper;
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
    private static final String WHITE = "#FFFFFF";
    private static final String FEMALE = "#FF8FBD";
    private static final String MALE = "#63A9FF";
    private static final String TRAITS_HEADER = "#F6C453";
    private static final String APPEARANCE_HEADER = "#74D7E8";

    private final Function<String, TwTraitConfig> traitConfigById;
    private final Function<String, TwTraitConfig> traitConfigByRole;
    private final Function<String, TwLevelingConfig> levelingConfigById;
    private final Function<String, TwLevelingConfig> levelingConfigByRole;
    private final Function<String, TwHappinessConfig> happinessConfigByRole;

    SpawnerTooltipPresentationService(@Nullable TranslationRegistry translationRegistry) {
        this(
                translationRegistry,
                TwTraitConfig::resolveById,
                TwTraitConfig::resolveForRole,
                TwLevelingConfig::resolveById,
                TwLevelingConfig::resolveForRole,
                TwHappinessConfig::resolveForRole
        );
    }

    SpawnerTooltipPresentationService(@Nullable TranslationRegistry translationRegistry,
                                      Function<String, TwTraitConfig> traitConfigById,
                                      Function<String, TwTraitConfig> traitConfigByRole,
                                      Function<String, TwLevelingConfig> levelingConfigById,
                                      Function<String, TwLevelingConfig> levelingConfigByRole) {
        this(translationRegistry, traitConfigById, traitConfigByRole, levelingConfigById,
                levelingConfigByRole, TwHappinessConfig::resolveForRole);
    }

    SpawnerTooltipPresentationService(@Nullable TranslationRegistry translationRegistry,
                                      Function<String, TwTraitConfig> traitConfigById,
                                      Function<String, TwTraitConfig> traitConfigByRole,
                                      Function<String, TwLevelingConfig> levelingConfigById,
                                      Function<String, TwLevelingConfig> levelingConfigByRole,
                                      Function<String, TwHappinessConfig> happinessConfigByRole) {
        this.traitConfigById = traitConfigById;
        this.traitConfigByRole = traitConfigByRole;
        this.levelingConfigById = levelingConfigById;
        this.levelingConfigByRole = levelingConfigByRole;
        this.happinessConfigByRole = happinessConfigByRole;
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
            sections.add(buildSection(
                    Message.translation("server.tamework.ui.spawnerTooltip.traits").color(TRAITS_HEADER),
                    traitLines));
        }
        List<Message> appearanceLines = buildAppearanceLines(attachments);
        if (!appearanceLines.isEmpty()) {
            sections.add(buildSection(
                    Message.translation("server.tamework.ui.spawnerTooltip.appearance").color(APPEARANCE_HEADER),
                    appearanceLines));
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
        Message level = resolveLevel(roleId, metadata);
        if (level != null) {
            parts.add(white(" - "));
            parts.add(level);
        }
        return join(parts);
    }

    @Nullable
    private Message resolveLevel(@Nullable String roleId, BsonDocument metadata) {
        Integer level = readInteger(metadata, TameworkMetadataKeys.LEVELING_LEVEL);
        if (level == null) {
            return null;
        }
        TwLevelingConfig config = resolveLevelingConfig(
                readString(metadata, TameworkMetadataKeys.LEVELING_CONFIG_ID),
                roleId
        );
        if (config == null) {
            return Message.translation("server.tamework.ui.spawnerTooltip.level")
                    .param("level", Math.max(1, level));
        }
        int maxLevel = config.getLevels().getMaxLevel();
        return Message.translation("server.tamework.ui.spawnerTooltip.levelWithMax")
                .param("level", Math.max(1, Math.min(level, maxLevel)))
                .param("max", maxLevel);
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
            lines.add(buildTraitLine(value, definition, roleId));
        }
        return lines;
    }

    /** Uses the same detached trait facts as companion portraits. */
    @Nullable
    ProgressionView.TraitsView capturedTraits(@Nullable String roleId, BsonDocument metadata) {
        String configId = readString(metadata, TameworkMetadataKeys.TRAITS_CONFIG_ID);
        String encoded = readString(metadata, TameworkMetadataKeys.TRAITS_VALUES);
        if (configId == null && encoded == null) {
            return null;
        }
        BsonValue seed = metadata.get(TameworkMetadataKeys.TRAITS_ROLL_SEED);
        long rollSeed = seed != null && (seed.isInt64() || seed.isInt32())
                ? seed.asNumber().longValue() : 0L;
        return TraitPresentationViewMapper.map(
                new TameworkTraitsComponent(configId, rollSeed, TraitValueCodec.decode(encoded)),
                resolveTraitConfig(configId, roleId));
    }

    private Message buildTraitLine(TameworkTraitsComponent.TraitValue traitValue,
                                   @Nullable TwTraitConfig.TraitDefinition definition,
                                   @Nullable String roleId) {
        Message label = resolveTraitLabel(traitValue.getId(), definition);
        if (definition == null) {
            return label;
        }
        String effect = normalizeEffectKey(definition.getEffectKey());
        double value = traitValue.getValue();
        return switch (effect) {
            case "needshungerdecaymultiplier" -> needUseLine(label, 1.0 - value, "food");
            case "needsthirstdecaymultiplier" -> needUseLine(label, 1.0 - value, "water");
            case "fertilitymultiplier" -> Message.join(label, white(": "), fertilityDescription(value));
            case "happinessgainmultiplier" -> happinessLine(label, value, roleId);
            case "sizemultiplier" -> sizeLine(label, definition, value);
            case "damagetakenmultiplier" -> Message.join(label, white(": "),
                    effectDescription("damageTaken", inverseDelta(value)));
            case "harvestdoubledropchancemultiplier" -> Message.join(label, white(": "),
                    effectDescription("harvest", Math.max(0.0, Math.min(1.0, value - 1.0))));
            case "damagedealtmultiplier" -> percentageLine(label, value - 1.0);
            case "maxhealthmultiplier", "movespeedmultiplier",
                    "harvestrecoveryspeedmultiplier", "fleecefiberyieldmultiplier",
                    "animalproductyieldmultiplier" -> percentageLine(label, value - 1.0);
            default -> label;
        };
    }

    private Message sizeLine(Message label, TwTraitConfig.TraitDefinition definition, double value) {
        double yieldBonus = TraitModifierService.resolveSizeMeatHideYieldBonus(definition, value);
        return Message.join(
                percentageLine(label, value - 1.0),
                Message.raw("\n"),
                Message.translation("server.tamework.traits.description.sizeYield")
                        .param("0", (yieldBonus > 0 ? "+" : "") + formatPercentMagnitude(yieldBonus))
        );
    }

    private Message happinessLine(Message label, double value, @Nullable String roleId) {
        TwHappinessConfig config = apply(happinessConfigByRole, roleId);
        if (config != null && config.getDisposition().getMode() == TwHappinessConfig.DispositionMode.FLAT) {
            double points = CompanionHappinessModifierService.resolveFlatDispositionOffset(
                    value, config.getDisposition());
            return Message.join(label, white(": "), effectDescription("happinessFlat", points, true));
        }
        return percentageLine(label, value - 1.0);
    }

    private static Message needUseLine(Message label, double savings, String resource) {
        String key = savings > 0.0
                ? "server.tamework.ui.spawnerTooltip.trait." + resource + "Less"
                : "server.tamework.ui.spawnerTooltip.trait." + resource + "More";
        return Message.join(label, white(": "), Message.translation(key)
                .param("0", formatPercentMagnitude(Math.abs(savings))));
    }

    private static Message percentageLine(Message label, double delta) {
        return Message.join(label, white(": "),
                Message.raw(signedPercent(delta)).color(deltaColor(delta)));
    }

    private static Message fertilityDescription(double value) {
        String key = value > 1.0 ? "fertilityPositive" : value < 1.0 ? "fertilityNegative" : "fertilityNeutral";
        return Message.translation("server.tamework.traits.description." + key);
    }

    private static Message effectDescription(String suffix, double delta) {
        return effectDescription(suffix, delta, false);
    }

    private static Message effectDescription(String suffix, double delta, boolean points) {
        String direction = delta > 0.0 ? "increase" : delta < 0.0 ? "decrease" : "neutral";
        Message description = Message.translation("server.tamework.traits.description." + suffix)
                .param("direction", Message.translation("server.tamework.traits.description.direction." + direction));
        return points
                ? description.param("points", formatDecimal(Math.abs(delta)))
                : description.param("percent", formatPercentMagnitude(Math.abs(delta)));
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
            String label = normalize(display.setLabel());
            String value = normalize(display.valueLabel());
            if (label != null) {
                Message heading = displayLabel(label);
                lines.add(value == null ? heading : Message.join(heading, white(": "), displayLabel(value)));
            }
        }
        return lines;
    }

    private static Message buildSection(Message header, List<Message> lines) {
        List<Message> parts = new ArrayList<>(lines.size() * 2 + 1);
        parts.add(header);
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

    private Message resolveTraitLabel(String traitId, @Nullable TwTraitConfig.TraitDefinition definition) {
        String fallback = prettifyId(definition != null ? definition.getId() : traitId);
        if (definition == null) {
            return white(fallback);
        }
        String configured = normalize(definition.getDisplayName());
        return displayLabel(configured == null ? fallback : configured);
    }

    private static Message displayLabel(String value) {
        return looksLikeTranslationKey(value)
                ? Message.translation(serverTranslationKey(value)).color(WHITE)
                : white(value);
    }

    private static boolean looksLikeTranslationKey(String value) {
        return value.indexOf('.') > 0 && value.indexOf(' ') < 0
                && value.chars().allMatch(character -> Character.isLetterOrDigit(character)
                || character == '.' || character == '_' || character == '-');
    }

    private static String serverTranslationKey(String key) {
        return key.startsWith("server.") ? key : "server." + key;
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

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value)
                .replaceFirst("\\.00$", "")
                .replaceFirst("(\\.\\d)0$", "$1");
    }

    private static String formatPercentMagnitude(double value) {
        return formatDecimal(value * 100.0);
    }

    private static String signedPercent(double value) {
        String prefix = value > 0.0 ? "+" : value < 0.0 ? "-" : "";
        return prefix + formatPercentMagnitude(Math.abs(value)) + "%";
    }

    private static String deltaColor(double delta) {
        return delta < 0.0 ? "#FFAEAE" : delta > 0.0 ? "#A2E8AE" : WHITE;
    }

    private static double inverseDelta(double value) {
        return value > 0.0 && Double.isFinite(value) ? 1.0 / value - 1.0 : 0.0;
    }

    private static String normalizeEffectKey(@Nullable String effectKey) {
        return effectKey == null ? "" : effectKey.trim().toLowerCase(Locale.ROOT);
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
