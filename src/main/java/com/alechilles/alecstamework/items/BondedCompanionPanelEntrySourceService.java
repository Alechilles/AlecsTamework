package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Converts durable bonded profiles into existing card view models and bonded features. */
final class BondedCompanionPanelEntrySourceService implements AutoCloseable {
    private final BondedCompanionPanelSnapshotCache cache;
    private final BondedCompanionPanelRecordSource records;
    private final BondedCompanionPanelFeaturePresentationSource presentations;
    private final HytaleBondedCompanionActionContextFactory contexts =
            new HytaleBondedCompanionActionContextFactory();
    private final BondedCompanionPanelFlightProjection flightProjection =
            new BondedCompanionPanelFlightProjection(
                    new BondedCompanionFlightModeReader());
    private final BondedCompanionPanelShoulderRideProjection shoulderRideProjection =
            new BondedCompanionPanelShoulderRideProjection();

    BondedCompanionPanelEntrySourceService(
            @Nonnull BondedCompanionPanelSnapshotCache cache,
            @Nonnull BondedCompanionPanelRecordSource records,
            @Nonnull BondedCompanionPanelFeaturePresentationSource presentations) {
        this.cache = java.util.Objects.requireNonNull(cache, "cache");
        this.records = java.util.Objects.requireNonNull(records, "records");
        this.presentations = java.util.Objects.requireNonNull(
                presentations, "presentations");
    }

    static BondedCompanionPanelEntrySourceService production(
            @Nullable java.util.function.Supplier<BondedCompanionApi> api) {
        return new BondedCompanionPanelEntrySourceService(
                BondedCompanionPanelSnapshotCache.production(api == null
                        ? BondedCompanionApi::unavailable : api),
                new BondedCompanionPanelRecordSource(),
                new BondedCompanionPanelFeaturePresentationSource(
                        System::currentTimeMillis));
    }

    CommandPanelEntrySourceService.CommandPanelSnapshot buildSnapshot(
            @Nonnull UUID ownerUuid, @Nonnull String rosterId,
            @Nullable String worldKey) {
        return buildSnapshot(ownerUuid, worldKey, records.snapshotFor(
                ownerUuid, rosterId, cache.peek(ownerUuid, rosterId)));
    }

    CommandPanelEntrySourceService.CommandPanelSnapshot buildSnapshot(
            @Nonnull Player player, @Nonnull Store<EntityStore> store,
            @Nonnull String rosterId) {
        String worldKey = player.getWorld() == null
                ? null : player.getWorld().getName();
        BondedCompanionPanelRecordSource.PanelSnapshot snapshot =
                records.snapshotFor(player.getUuid(), rosterId,
                        cache.peek(player.getUuid(), rosterId));
        snapshot = withLivePresentation(player, store, snapshot);
        ArrayList<LinkedNpcEntry> entries = new ArrayList<>(
                snapshot.records().size());
        for (var record : snapshot.records()) entries.add(entry(record));
        return new CommandPanelEntrySourceService.CommandPanelSnapshot(
                List.copyOf(entries), presentations.snapshot(
                        player.getUuid(), worldKey, snapshot,
                        profile -> contexts.create(player, store,
                                profile.roleId(), profile.state()
                                        == BondedCompanionStateView.STORED)),
                emptyStateKey(snapshot));
    }

    CommandPanelEntrySourceService.CommandPanelSnapshot buildSnapshot(
            @Nonnull UUID ownerUuid, @Nullable String worldKey,
            @Nonnull BondedCompanionPanelRecordSource.PanelSnapshot snapshot) {
        ArrayList<LinkedNpcEntry> entries = new ArrayList<>(snapshot.records().size());
        for (var record : snapshot.records()) entries.add(entry(record));
        return new CommandPanelEntrySourceService.CommandPanelSnapshot(
                List.copyOf(entries),
                presentations.snapshot(ownerUuid, worldKey, snapshot),
                emptyStateKey(snapshot));
    }

    private LinkedNpcEntry entry(BondedCompanionPanelRecordSource.PanelRecord record) {
        var profile = record.profile();
        int maxHealth = healthValue(profile.snapshotPresentationData(), "maxHealth", 100);
        int currentHealth = healthValue(profile.snapshotPresentationData(), "currentHealth", -1);
        if (currentHealth < 0) currentHealth = percentValue(
                profile.snapshotPresentationData().get("healthPercent"), maxHealth);
        int happiness = percentValue(
                profile.snapshotPresentationData().get("happiness"), 100);
        int hunger = percentValue(
                profile.snapshotPresentationData().get("hunger"), 100);
        int thirst = percentValue(
                profile.snapshotPresentationData().get("thirst"), 100);
        boolean dead = profile.state() == BondedCompanionStateView.DEAD;
        return new LinkedNpcEntry(
                record.presentationUuid(), fallback(profile.displayName(), profile.species()),
                profile.gender(), currentHealth, maxHealth, happiness, 100,
                happiness, "", hunger, 100, thirst, 100,
                !dead && maxHealth > 0, false, dead, false, false, false,
                0L, null, null, null, new LinkedNpcTraitIndicator[0],
                false, false, false, false,
                false, profile.state() == BondedCompanionStateView.ACTIVE,
                null, profile.species(), null, null, null,
                false, false, false, 0L, 0D, false,
                false, 0L, 0D, false, false, 0L);
    }

    /**
     * Live names and vitals supersede the durable snapshot for an active card.
     * Store and dismiss remain the only paths that persist those values.
     */
    private BondedCompanionPanelRecordSource.PanelSnapshot withLivePresentation(
            Player player, Store<EntityStore> store,
            BondedCompanionPanelRecordSource.PanelSnapshot snapshot
    ) {
        if (snapshot.records().isEmpty()) {
            return snapshot;
        }
        ArrayList<BondedCompanionPanelRecordSource.PanelRecord> updated =
                new ArrayList<>(snapshot.records().size());
        boolean changed = false;
        for (var record : snapshot.records()) {
            BondedCompanionProfileView profile = record.profile();
            BondedCompanionProfileView overlay =
                    BondedCompanionPanelLiveProfileOverlay.withDisplayName(
                            profile, liveDisplayName(player, store, profile));
            overlay = BondedCompanionPanelLiveProfileOverlay.withHealth(
                    overlay, liveHealth(player, store, profile));
            overlay = BondedCompanionPanelLiveProfileOverlay.withProgression(
                    overlay, liveProgression(player, store, profile));
            overlay = BondedCompanionPanelLiveProfileOverlay.withFlightMode(
                    overlay, liveFlightMode(player, store, profile));
            overlay = BondedCompanionPanelLiveProfileOverlay.withShoulderRide(
                    overlay, liveShoulderRide(player, store, profile));
            updated.add(overlay == profile ? record
                    : new BondedCompanionPanelRecordSource.PanelRecord(
                            record.presentationUuid(), overlay));
            changed |= overlay != profile;
        }
        return changed ? new BondedCompanionPanelRecordSource.PanelSnapshot(
                List.copyOf(updated), snapshot.generation(), snapshot.state(),
                snapshot.trusted()) : snapshot;
    }

    @Nullable
    private String liveDisplayName(
            Player player, Store<EntityStore> store,
            BondedCompanionProfileView profile
    ) {
        ComponentType<EntityStore, TameworkNpcNameComponent> type =
                TameworkNpcNameComponent.getComponentType();
        if (type == null) {
            return null;
        }
        Ref<EntityStore> reference = exactActiveReference(player, store, profile);
        if (reference == null) {
            return null;
        }
        TameworkNpcNameComponent name = store.getComponent(reference, type);
        return name == null ? null : name.getName();
    }

    @Nullable
    private CompanionHealthStateService.HealthSnapshot liveHealth(
            Player player, Store<EntityStore> store,
            BondedCompanionProfileView profile
    ) {
        Ref<EntityStore> reference = exactActiveReference(player, store, profile);
        return CompanionHealthStateService.captureHealth(reference, store);
    }

    @Nullable
    private BondedCompanionPanelLiveProfileOverlay.ProgressionSnapshot liveProgression(
            Player player, Store<EntityStore> store,
            BondedCompanionProfileView profile
    ) {
        Ref<EntityStore> npcRef = exactActiveReference(player, store, profile);
        return liveProgression(npcRef, store, profile.roleId());
    }

    /** Resolves only persisted progression components for a live panel projection. */
    @Nullable
    static BondedCompanionPanelLiveProfileOverlay.ProgressionSnapshot liveProgression(
            @Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store,
            @Nullable String roleId
    ) {
        if (!hasLiveLevelingComponent(npcRef, store)) {
            return null;
        }
        CompanionLevelingService.LevelingSnapshot leveling =
                CompanionLevelingService.resolveSnapshot(npcRef, store, roleId);
        if (leveling == null) {
            return null;
        }
        TameworkTalentsComponent talents = safeTalents(npcRef, store);
        TwTalentConfig talentConfig = resolveTalentConfig(npcRef, store, talents);
        return new BondedCompanionPanelLiveProfileOverlay.ProgressionSnapshot(
                leveling.configId(), leveling.level(), leveling.currentXp(),
                talentConfig == null ? null : talentConfig.getId(),
                talentConfig == null ? null : talents.getSpentPoints());
    }

    @Nullable
    private static TwTalentConfig resolveTalentConfig(
            @Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store,
            @Nullable TameworkTalentsComponent talents
    ) {
        if (talents == null) return null;
        try {
            return CompanionTalentService.resolveTalentConfig(npcRef, store);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static TameworkTalentsComponent safeTalents(
            @Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store
    ) {
        ComponentType<EntityStore, TameworkTalentsComponent> type =
                TameworkTalentsComponent.getComponentType();
        return npcRef == null || type == null || store == null
                ? null : store.getComponent(npcRef, type);
    }

    static boolean hasLiveLevelingComponent(
            @Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store
    ) {
        ComponentType<EntityStore, TameworkLevelingComponent> type =
                TameworkLevelingComponent.getComponentType();
        return npcRef != null && store != null && type != null
                && store.getComponent(npcRef, type) != null;
    }

    @Nullable
    private static Ref<EntityStore> exactActiveReference(
            @Nullable Player player, @Nullable Store<EntityStore> store,
            @Nonnull BondedCompanionProfileView profile
    ) {
        if (player == null || store == null
                || profile.state() != BondedCompanionStateView.ACTIVE
                || profile.activeLease() == null || player.getWorld() == null
                || profile.activeLease().liveNpcUuid() == null
                || !profile.activeLease().worldKey().equals(player.getWorld().getName())) {
            return null;
        }
        Ref<EntityStore> reference = player.getWorld().getEntityRef(
                profile.activeLease().liveNpcUuid());
        return reference != null && reference.isValid()
                && reference.getStore() == store ? reference : null;
    }

    /**
     * Projects flight controls only from the exact current world entity and
     * its role-scoped effective configuration.
     */
    @Nonnull
    private Optional<Boolean> liveFlightMode(
            Player player, Store<EntityStore> store,
            BondedCompanionProfileView profile
    ) {
        if (profile.state() != BondedCompanionStateView.ACTIVE
                || profile.activeLease() == null || store == null
                || player.getWorld() == null
                || !profile.activeLease().worldKey().equals(
                        player.getWorld().getName())) {
            return Optional.empty();
        }
        try {
            Ref<EntityStore> reference = player.getWorld().getEntityRef(
                    profile.activeLease().liveNpcUuid());
            return flightProjection.read(profile, player.getWorld().getName(),
                    reference, store);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    /** Projects the exact live NPC-to-player shoulder attachment state. */
    @Nonnull
    private Optional<Boolean> liveShoulderRide(
            Player player, Store<EntityStore> store,
            BondedCompanionProfileView profile
    ) {
        Ref<EntityStore> npcRef = exactActiveReference(player, store, profile);
        Ref<EntityStore> playerRef = player == null ? null : player.getReference();
        String worldKey = player == null || player.getWorld() == null
                ? null : player.getWorld().getName();
        return shoulderRideProjection.read(profile, worldKey, npcRef,
                playerRef, store);
    }

    private static int healthValue(java.util.Map<String, String> data,
                                   String key, int fallback) {
        try {
            double value = Double.parseDouble(data.get(key));
            if (!Double.isFinite(value)) return fallback;
            return (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, Math.round(value)));
        }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int percentValue(String value, int scale) {
        if (value == null) return 0;
        try {
            double parsed = Double.parseDouble(value);
            double normalized = parsed >= 0D && parsed <= 1D ? parsed * 100D : parsed;
            return (int) Math.round(Math.max(0D, Math.min(100D, normalized))
                    * Math.max(1, scale) / 100D);
        } catch (RuntimeException ignored) { return 0; }
    }

    private static String fallback(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) return primary;
        if (secondary != null && !secondary.isBlank()) return secondary;
        return "Bonded Companion";
    }

    void evictOwner(@Nullable UUID ownerUuid) {
        cache.evictOwner(ownerUuid);
    }

    void warm(@Nullable UUID ownerUuid, @Nullable String rosterId) {
        if (ownerUuid != null && rosterId != null && !rosterId.isBlank()) {
            cache.warm(ownerUuid, rosterId);
        }
    }

    AutoCloseable subscribe(@Nonnull UUID ownerUuid, @Nonnull String rosterId,
                            @Nonnull Runnable listener) {
        return cache.subscribe(ownerUuid, rosterId, listener);
    }

    /** Refreshes the immutable profile generation after a rejected mutation. */
    void refresh(@Nullable UUID ownerUuid, @Nullable String rosterId) {
        if (ownerUuid != null && rosterId != null && !rosterId.isBlank()) {
            cache.refresh(ownerUuid, rosterId);
        }
    }

    /** Returns one currently trusted durable profile without blocking for a refresh. */
    @Nullable
    BondedCompanionProfileView currentTrustedProfile(UUID ownerUuid,
                                                      String rosterId,
                                                      String profileId) {
        var snapshot = records.snapshotFor(ownerUuid, rosterId,
                cache.peek(ownerUuid, rosterId));
        if (!snapshot.trusted() || snapshot.state()
                != BondedCompanionPanelSnapshotCache.State.READY) return null;
        return snapshot.records().stream()
                .map(BondedCompanionPanelRecordSource.PanelRecord::profile)
                .filter(profile -> profileId.equals(profile.profileId()))
                .findFirst().orElse(null);
    }

    void refreshBoundApi() {
        cache.refreshBoundApi();
    }

    @Nullable
    private static String emptyStateKey(
            BondedCompanionPanelRecordSource.PanelSnapshot snapshot) {
        if (!snapshot.records().isEmpty()) return null;
        return switch (snapshot.state()) {
            case REFRESHING -> "tamework.ui.linkedPanel.bonded.loading";
            case FAILED, CLOSED -> "tamework.ui.linkedPanel.bonded.unavailable";
            case READY -> null;
        };
    }

    @Override
    public void close() {
        cache.close();
    }

}
