package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Physical authority checks shared by ordinary command-item boundaries.
 *
 * <p>Bonded companions and Horn stacks are authoritative to their roster
 * path. Generic pages may neither present nor mutate them, including when a
 * callback was created before a reload changed the physical tool's config.</p>
 *
 * <p>What a loaded body is comes from its {@link TameworkCompanionComponent}
 * stamp and the companion index record the stamp names, the same evidence the
 * bonded command path uses. A body saved by 3.x or 4.x and a body spawned by
 * this version therefore get the same answer. The retired projection identity
 * component is not read.</p>
 */
final class CommandGenericTargetAuthority {
    /** What the companion index says about the profile a body is stamped with. */
    enum Standing {
        /** No record, or an ordinary companion: generic items may act on it. */
        ORDINARY,
        /** A command-family roster member that is not bonded. */
        ROSTER_MEMBER,
        /** A bonded companion: only its bonded item may act on it. */
        BONDED
    }

    /** Index lookup by profile id; tests replace it through {@link #standingsForTest}. */
    private static volatile Function<UUID, Standing> standings =
            CommandGenericTargetAuthority::indexStanding;

    private CommandGenericTargetAuthority() {
    }

    /** Replaces the index lookup and returns the previous one; null restores the default. */
    @Nonnull
    static Function<UUID, Standing> standingsForTest(@Nullable Function<UUID, Standing> lookup) {
        Function<UUID, Standing> previous = standings;
        standings = lookup != null ? lookup : CommandGenericTargetAuthority::indexStanding;
        return previous;
    }

    /**
     * Reads the live index. While companion saving is paused there is no index and every body
     * counts as ordinary: the bonded items are off as well, and blocking every command item
     * would be worse than the missing restriction.
     */
    @Nonnull
    private static Standing indexStanding(@Nonnull UUID profileId) {
        Tamework plugin = Tamework.getInstance();
        CompanionQueries companions = plugin == null ? null : plugin.getCompanionQueries();
        CompanionRecord record = companions == null ? null : companions.get(profileId);
        if (record == null) {
            return Standing.ORDINARY;
        }
        if (record.bonded()) {
            return Standing.BONDED;
        }
        return record.rosterId() != null ? Standing.ROSTER_MEMBER : Standing.ORDINARY;
    }

    /**
     * The standing of a loaded body, or null when it cannot be read (invalid reference, stamp
     * type not registered, lookup failure). Callers treat null as "not allowed".
     */
    @Nullable
    private static Standing standing(
            @Nullable Ref<EntityStore> reference,
            @Nullable ComponentAccessor<EntityStore> components
    ) {
        if (reference == null || !reference.isValid() || components == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkCompanionComponent> type =
                TameworkCompanionComponent.getComponentType();
        if (type == null) {
            return null;
        }
        try {
            TameworkCompanionComponent stamp = components.getComponent(reference, type);
            UUID profileId = stamp == null ? null : stamp.getProfileId();
            return profileId == null ? Standing.ORDINARY : standings.apply(profileId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * True when the body is a command-family roster member that is not bonded. Its family item's
     * panel owns it, so one-way generic conversions leave it alone (the same rule the owned panel
     * applies to roster records).
     */
    static boolean isRosterMember(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store
    ) {
        return standing(reference, store) == Standing.ROSTER_MEMBER;
    }

    static boolean allowsNearbyPresentation(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store
    ) {
        return allowsGenericTargetMutation(reference, store);
    }

    /**
     * Returns whether a generic command action may act on this loaded target:
     * every body except a bonded companion. A body whose standing cannot be
     * read is denied: destructive generic actions must never win an authority
     * race against a bonded companion.
     */
    static boolean allowsGenericTargetMutation(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store
    ) {
        return allowsGenericTargetMutation(
                reference, (ComponentAccessor<EntityStore>) store);
    }

    static boolean allowsGenericTargetMutation(
            @Nullable Ref<EntityStore> reference,
            @Nullable ComponentAccessor<EntityStore> components
    ) {
        Standing standing = standing(reference, components);
        return standing != null && standing != Standing.BONDED;
    }

    static boolean allowsCurrentGenericCallback(
            @Nullable ItemStack physicalStack,
            @Nullable TwCommandItemConfig openTimeConfig,
            @Nullable TwCommandItemConfig currentPhysicalConfig
    ) {
        return physicalStack != null && !physicalStack.isEmpty()
                && CommandRosterStorageBoundary.allowsGenericRosterActions(
                        openTimeConfig)
                && CommandRosterStorageBoundary.allowsGenericRosterActions(
                        currentPhysicalConfig);
    }

    /**
     * Rechecks a callback against the registry generation captured when its
     * page opened. The revision check before and after lookup closes a reload
     * race without relying on config object identity or config-id overrides.
     */
    static boolean allowsCurrentGenericCallback(
            @Nullable ItemStack physicalStack,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandItemRegistry registry
    ) {
        if (registry == null || registry.revision() != openedRegistryRevision
                || physicalStack == null || physicalStack.isEmpty()) {
            return false;
        }
        return registry.revision() == openedRegistryRevision
                && CommandRosterStorageBoundary.allowsGenericRosterActions(
                        openTimeConfig)
                && isExactRegisteredConfig(
                        physicalStack, openTimeConfig, registry)
                && registry.revision() == openedRegistryRevision;
    }

    static boolean allowsCurrentGenericCallback(
            @Nullable ItemStack physicalStack,
            @Nullable String openedPhysicalItemId,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandItemRegistry registry
    ) {
        return physicalStack != null
                && Objects.equals(openedPhysicalItemId,
                        physicalStack.getItemId())
                && allowsCurrentGenericCallback(
                        physicalStack, openTimeConfig,
                        openedRegistryRevision, registry);
    }

    /**
     * Revalidates a bonded page against one current tool and one unchanged
     * registry generation. Stable config IDs remain valid overrides; config
     * object identity is deliberately irrelevant across resolution paths.
     */
    static boolean allowsCurrentBondedCallback(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable String openedPhysicalItemId,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandToolInventoryService toolInventoryService,
            @Nullable CommandItemRegistry registry
    ) {
        ItemStack stack = toolInventoryService == null ? null
                : toolInventoryService.findUniqueToolStack(player, toolId);
        return allowsCurrentBondedCallback(
                stack, openedPhysicalItemId, openTimeConfig,
                openedRegistryRevision, registry);
    }

    static boolean allowsCurrentBondedCallback(
            @Nullable ItemStack physicalStack,
            @Nullable String openedPhysicalItemId,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandItemRegistry registry
    ) {
        if (registry == null || registry.revision() != openedRegistryRevision
                || physicalStack == null || physicalStack.isEmpty()
                || !Objects.equals(openedPhysicalItemId,
                        physicalStack.getItemId())
                || openTimeConfig == null
                || !openTimeConfig.usesBondedCompanionRoster()) {
            return false;
        }
        TwCommandItemConfig current = resolveCurrentConfig(
                physicalStack, openTimeConfig, registry);
        return registry.revision() == openedRegistryRevision
                && allowsCurrentBondedCallback(
                        physicalStack, openedPhysicalItemId,
                        openTimeConfig, current);
    }

    static boolean allowsCurrentBondedCallback(
            @Nullable ItemStack physicalStack,
            @Nullable String openedPhysicalItemId,
            @Nullable TwCommandItemConfig openTimeConfig,
            @Nullable TwCommandItemConfig currentPhysicalConfig
    ) {
        return physicalStack != null && !physicalStack.isEmpty()
                && Objects.equals(openedPhysicalItemId,
                        physicalStack.getItemId())
                && openTimeConfig != null
                && openTimeConfig.usesBondedCompanionRoster()
                && currentPhysicalConfig != null
                && currentPhysicalConfig.isEnabled()
                && currentPhysicalConfig.usesBondedCompanionRoster()
                && Objects.equals(openTimeConfig.getBondedRosterId(),
                        currentPhysicalConfig.getBondedRosterId());
    }

    /** Resolves exactly one current stack before accepting a generic callback. */
    static boolean allowsCurrentGenericCallback(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable TwCommandItemConfig openTimeConfig,
            long openedRegistryRevision,
            @Nullable CommandToolInventoryService toolInventoryService,
            @Nullable CommandItemRegistry registry
    ) {
        ItemStack stack = toolInventoryService == null ? null
                : toolInventoryService.findUniqueToolStack(player, toolId);
        return allowsCurrentGenericCallback(
                stack, openTimeConfig, openedRegistryRevision, registry);
    }

    static boolean allowsGenericCullRepair(
            @Nullable ItemStack physicalStack,
            @Nullable TwCommandItemConfig currentPhysicalConfig
    ) {
        return physicalStack != null && !physicalStack.isEmpty()
                && CommandRosterStorageBoundary.allowsGenericRosterActions(
                        currentPhysicalConfig);
    }

    private static TwCommandItemConfig resolveCurrentConfig(
            ItemStack physicalStack,
            TwCommandItemConfig openTimeConfig,
            CommandItemRegistry registry
    ) {
        String configId = openTimeConfig == null ? null : openTimeConfig.getId();
        TwCommandItemConfig override = configId == null || configId.isBlank()
                ? null : registry.getByConfigId(configId);
        return override != null ? override : registry.get(physicalStack.getItemId());
    }

    private static boolean isExactRegisteredConfig(
            ItemStack physicalStack,
            TwCommandItemConfig openTimeConfig,
            CommandItemRegistry registry
    ) {
        if (openTimeConfig == null) {
            return false;
        }
        String configId = openTimeConfig.getId();
        if (configId != null && !configId.isBlank()
                && registry.getByConfigId(configId) == openTimeConfig) {
            return true;
        }
        return registry.get(physicalStack.getItemId()) == openTimeConfig;
    }
}
