package com.alechilles.alecstamework.config.overrides;

import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.config.assets.TwAttachmentDisplayConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.config.assets.TwDebugConfig;
import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNamesConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.AssetStore;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Canonical V1 family definitions for the Tamework config editor.
 */
public enum TwConfigFamily {
    GLOBAL("global", "Global", "Tamework/Global", true, true),
    COMPANION("companion", "Companion", "Tamework/Companion", true, true),
    INTERACTION("interaction", "Interaction", "Tamework/Interactions", true, true),
    SPAWNER("spawner", "Spawner Items", "Tamework/Items/Spawners", true, true),
    NAME_ITEM("name-item", "Name Items", "Tamework/Items/Naming", true, true),
    NAMES("names", "Names", "Tamework/Names", true, true),
    COMMAND_ITEM("command-item", "Command Items", "Tamework/Items/Commands", true, true),
    FOOD("food", "Food", "Tamework/Food", true, true),
    HAPPINESS("happiness", "Happiness", "Tamework/Happiness", true, true),
    NEEDS("needs", "Needs", "Tamework/Needs", true, true),
    BREEDING("breeding", "Breeding", "Tamework/Breeding", true, true),
    ATTACHMENT_MIGRATION("attachment-migration", "Attachment Migrations", "Tamework/AttachmentMigrations", true, true),
    ATTACHMENT_DISPLAY("attachment-display", "Attachment Displays", "Tamework/AttachmentDisplays", true, true),
    LEVELING("leveling", "Leveling", "Tamework/Leveling", true, true),
    TRAIT("trait", "Traits", "Tamework/Traits", true, true),
    TALENT("talent", "Talents", "Tamework/Talents", true, true),
    COOP("coop", "Coops", "Tamework/Items/Coops", true, true),
    DEBUG("debug", "Debug", "Tamework/Debug", true, true),
    OTHER("other", "Other", "", false, false);

    private final String id;
    private final String displayName;
    private final String storePath;
    private final boolean editableInV1;
    private final boolean knownType;

    TwConfigFamily(@Nonnull String id,
                   @Nonnull String displayName,
                   @Nonnull String storePath,
                   boolean editableInV1,
                   boolean knownType) {
        this.id = id;
        this.displayName = displayName;
        this.storePath = storePath;
        this.editableInV1 = editableInV1;
        this.knownType = knownType;
    }

    @Nonnull
    public String getId() {
        return id;
    }

    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    @Nonnull
    public String getStorePath() {
        return storePath;
    }

    public boolean isEditableInV1() {
        return editableInV1;
    }

    public boolean isKnownType() {
        return knownType;
    }

    @Nonnull
    public String getServerRelativePrefix() {
        if (storePath == null || storePath.isBlank()) {
            return "Server";
        }
        return "Server/" + storePath;
    }

    public boolean matchesStorePath(@Nullable String candidatePath) {
        if (candidatePath == null || candidatePath.isBlank()) {
            return false;
        }
        return storePath.equalsIgnoreCase(candidatePath.trim());
    }

    @Nullable
    public static TwConfigFamily fromStorePath(@Nullable String candidatePath) {
        if (candidatePath == null || candidatePath.isBlank()) {
            return null;
        }
        String normalized = candidatePath.trim().toLowerCase(Locale.ROOT);
        for (TwConfigFamily family : values()) {
            if (family == OTHER) {
                continue;
            }
            if (family.storePath.toLowerCase(Locale.ROOT).equals(normalized)) {
                return family;
            }
        }
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public AssetStore<String, ?, ? extends AssetMap<String, ?>> getAssetStore() {
        return switch (this) {
            case GLOBAL -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwGlobalConfig.getAssetStore();
            case COMPANION -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwCompanionConfig.getAssetStore();
            case INTERACTION -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwInteractionConfig.getAssetStore();
            case SPAWNER -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwSpawnerConfig.getAssetStore();
            case NAME_ITEM -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwNameItemConfig.getAssetStore();
            case NAMES -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwNamesConfig.getAssetStore();
            case COMMAND_ITEM ->
                    (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwCommandItemConfig.getAssetStore();
            case FOOD -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwFoodConfig.getAssetStore();
            case HAPPINESS -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwHappinessConfig.getAssetStore();
            case NEEDS -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwNeedsConfig.getAssetStore();
            case BREEDING -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwBreedingConfig.getAssetStore();
            case ATTACHMENT_MIGRATION ->
                    (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwAttachmentMigrationConfig.getAssetStore();
            case ATTACHMENT_DISPLAY ->
                    (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwAttachmentDisplayConfig.getAssetStore();
            case LEVELING -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwLevelingConfig.getAssetStore();
            case TRAIT -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwTraitConfig.getAssetStore();
            case TALENT -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwTalentConfig.getAssetStore();
            case COOP -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwCoopConfig.getAssetStore();
            case DEBUG -> (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwDebugConfig.getAssetStore();
            case OTHER -> null;
        };
    }
}
