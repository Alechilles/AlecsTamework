package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkDynamicAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentApplicationService;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentConfigIndex;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentNpcSnapshot;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentResolution;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentRuleResolver;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentSnapshotReader;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Periodically evaluates configured dynamic attachment rules and stores the selected attachment overlay.
 */
public final class DynamicAttachmentEvaluationSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 1_500L;
    private static final int SWEEP_JITTER_MS = 500;
    private static final DynamicAttachmentResolution EMPTY_RESOLUTION =
            new DynamicAttachmentResolution(Map.of(), Map.of());

    private final StoreScopedState<TickState> statesByStore = new StoreScopedState<>(TickState::new);
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    private final ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsComponentType;
    private final ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> dynamicAttachmentsComponentType;
    private final DynamicAttachmentSnapshotReader snapshotReader;

    public DynamicAttachmentEvaluationSystem() {
        this(
                NPCEntity.getComponentType(),
                TameworkAttachmentsComponent.getComponentType(),
                TameworkDynamicAttachmentsComponent.getComponentType(),
                TameworkOwnerComponent.getComponentType(),
                TameworkTamedComponent.getComponentType(),
                TameworkLifeStageComponent.getComponentType(),
                TameworkHappinessComponent.getComponentType(),
                TameworkNeedsComponent.getComponentType(),
                TameworkTraitsComponent.getComponentType(),
                TameworkCommandLinksComponent.getComponentType()
        );
    }

    public DynamicAttachmentEvaluationSystem(
            @Nullable ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nullable ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsComponentType,
            @Nullable ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> dynamicAttachmentsComponentType,
            @Nullable ComponentType<EntityStore, TameworkOwnerComponent> ownerComponentType,
            @Nullable ComponentType<EntityStore, TameworkTamedComponent> tamedComponentType,
            @Nullable ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageComponentType,
            @Nullable ComponentType<EntityStore, TameworkHappinessComponent> happinessComponentType,
            @Nullable ComponentType<EntityStore, TameworkNeedsComponent> needsComponentType,
            @Nullable ComponentType<EntityStore, TameworkTraitsComponent> traitsComponentType,
            @Nullable ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksComponentType) {
        this.npcComponentType = npcComponentType;
        this.attachmentsComponentType = attachmentsComponentType;
        this.dynamicAttachmentsComponentType = dynamicAttachmentsComponentType;
        this.snapshotReader = new DynamicAttachmentSnapshotReader(
                ownerComponentType,
                tamedComponentType,
                lifeStageComponentType,
                happinessComponentType,
                needsComponentType,
                traitsComponentType,
                commandLinksComponentType
        );
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        TickState tickState = statesByStore.get(store);
        long nowMs = System.currentTimeMillis();
        if (nowMs < tickState.nextSweepAtMs) {
            return;
        }
        tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS + jitterMs(store);

        if (npcComponentType == null || attachmentsComponentType == null || dynamicAttachmentsComponentType == null) {
            return;
        }

        DynamicAttachmentConfigIndex index = DynamicAttachmentConfigIndex.current();
        HashSet<UUID> activeNpcIds = new HashSet<>();
        store.forEachChunk(
                Query.and(npcComponentType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        evaluateNpc(chunk, i, commandBuffer, store, tickState, index, activeNpcIds);
                    }
                }
        );
        pruneInactiveKeys(tickState.lastFingerprintByNpc, activeNpcIds);
    }

    private void evaluateNpc(@Nonnull ArchetypeChunk<EntityStore> chunk,
                             int indexInChunk,
                             @Nonnull CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull Store<EntityStore> store,
                             @Nonnull TickState tickState,
                             @Nonnull DynamicAttachmentConfigIndex configIndex,
                             @Nonnull HashSet<UUID> activeNpcIds) {
        Ref<EntityStore> ref = chunk.getReferenceTo(indexInChunk);
        NPCEntity npc = chunk.getComponent(indexInChunk, npcComponentType);
        if (ref == null || !ref.isValid() || npc == null || npc.getUuid() == null) {
            return;
        }
        UUID npcUuid = npc.getUuid();
        activeNpcIds.add(npcUuid);

        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        List<TwDynamicAttachmentsConfig.RoleRuleEntry> rules = configIndex.rulesForRole(roleId);
        if (rules.isEmpty()) {
            restoreInactiveOverlayForUnconfiguredRole(ref, commandBuffer, store, tickState, npcUuid);
            return;
        }

        DynamicAttachmentNpcSnapshot snapshot = snapshotReader.read(ref, store);
        DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(snapshot, rules);
        TameworkAttachmentsComponent storedAttachments = store.getComponent(ref, attachmentsComponentType);
        TameworkDynamicAttachmentsComponent dynamicOverlay = store.getComponent(ref, dynamicAttachmentsComponentType);
        DynamicAttachmentFingerprint fingerprint = fingerprint(snapshot, resolution, storedAttachments, dynamicOverlay);
        if (fingerprint.equals(tickState.lastFingerprintByNpc.get(npcUuid))) {
            return;
        }

        DynamicAttachmentApplicationService.ApplyResult result =
                DynamicAttachmentApplicationService.applyResolution(storedAttachments, dynamicOverlay, resolution);
        if (result.changed()) {
            commandBuffer.putComponent(ref, attachmentsComponentType, result.attachments());
            commandBuffer.putComponent(ref, dynamicAttachmentsComponentType, result.overlay());
        }
        tickState.lastFingerprintByNpc.put(npcUuid, fingerprint);
    }

    private void restoreInactiveOverlayForUnconfiguredRole(@Nonnull Ref<EntityStore> ref,
                                                           @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                           @Nonnull Store<EntityStore> store,
                                                           @Nonnull TickState tickState,
                                                           @Nonnull UUID npcUuid) {
        TameworkDynamicAttachmentsComponent dynamicOverlay = store.getComponent(ref, dynamicAttachmentsComponentType);
        if (dynamicOverlay == null || !dynamicOverlay.hasActiveSlots()) {
            tickState.lastFingerprintByNpc.remove(npcUuid);
            return;
        }
        TameworkAttachmentsComponent storedAttachments = store.getComponent(ref, attachmentsComponentType);
        DynamicAttachmentApplicationService.ApplyResult result =
                restoreUnconfiguredRoleForTest(storedAttachments, dynamicOverlay);
        if (result.changed()) {
            commandBuffer.putComponent(ref, attachmentsComponentType, result.attachments());
            commandBuffer.putComponent(ref, dynamicAttachmentsComponentType, result.overlay());
        }
        tickState.lastFingerprintByNpc.remove(npcUuid);
    }

    static boolean shouldEvaluateRole(@Nullable String roleId, @Nullable DynamicAttachmentConfigIndex index) {
        return index != null && index.hasRulesForRole(roleId);
    }

    @Nonnull
    static DynamicAttachmentFingerprint fingerprintForTest(@Nullable DynamicAttachmentNpcSnapshot snapshot) {
        return fingerprint(snapshot, null, null, null);
    }

    @Nonnull
    static DynamicAttachmentApplicationService.ApplyResult restoreUnconfiguredRoleForTest(
            @Nullable TameworkAttachmentsComponent storedAttachments,
            @Nullable TameworkDynamicAttachmentsComponent dynamicOverlay) {
        return DynamicAttachmentApplicationService.applyResolution(
                storedAttachments,
                dynamicOverlay,
                EMPTY_RESOLUTION
        );
    }

    @Nonnull
    private static DynamicAttachmentFingerprint fingerprint(
            @Nullable DynamicAttachmentNpcSnapshot snapshot,
            @Nullable DynamicAttachmentResolution resolution,
            @Nullable TameworkAttachmentsComponent storedAttachments,
            @Nullable TameworkDynamicAttachmentsComponent dynamicOverlay) {
        Map<String, String> permanent = resolution == null ? Map.of() : resolution.permanentAttachments();
        Map<String, DynamicAttachmentResolution.TemporaryAttachment> temporary =
                resolution == null ? Map.of() : resolution.temporaryAttachments();
        Map<String, String> stored = storedAttachments == null ? Map.of() : storedAttachments.getAttachmentIds();
        TameworkDynamicAttachmentsComponent.ActiveSlot[] activeSlots = dynamicOverlay == null
                ? new TameworkDynamicAttachmentsComponent.ActiveSlot[0]
                : dynamicOverlay.getActiveSlots();
        return new DynamicAttachmentFingerprint(
                snapshot == null ? null : snapshot.getRoleId(),
                snapshot == null ? null : snapshot.getDisplayName(),
                snapshot == null ? null : snapshot.getOwnerPresent(),
                snapshot == null ? null : snapshot.getTamed(),
                snapshot == null ? null : snapshot.getGender(),
                snapshot == null ? null : snapshot.getLifeStage(),
                snapshot == null ? null : snapshot.getHappiness(),
                stableMapHash(snapshot == null ? null : snapshot.getNeeds()),
                mapSize(snapshot == null ? null : snapshot.getNeeds()),
                stableMapHash(snapshot == null ? null : snapshot.getTraits()),
                mapSize(snapshot == null ? null : snapshot.getTraits()),
                stableMapHash(snapshot == null ? null : snapshot.getCommandStates()),
                mapSize(snapshot == null ? null : snapshot.getCommandStates()),
                stableMapHash(permanent),
                mapSize(permanent),
                stableMapHash(temporary),
                mapSize(temporary),
                stableMapHash(stored),
                mapSize(stored),
                stableActiveSlotsHash(activeSlots),
                activeSlots.length
        );
    }

    private static int stableActiveSlotsHash(@Nonnull TameworkDynamicAttachmentsComponent.ActiveSlot[] activeSlots) {
        int hash = 1;
        for (TameworkDynamicAttachmentsComponent.ActiveSlot slot : activeSlots) {
            if (slot == null) {
                hash = 31 * hash;
                continue;
            }
            hash = 31 * hash + Objects.hash(
                    slot.getSlot(),
                    slot.getPreviousValue(),
                    slot.isHasPreviousValue(),
                    slot.getAppliedValue(),
                    slot.getRuleKey()
            );
        }
        return hash;
    }

    private static int stableMapHash(@Nullable Map<?, ?> values) {
        return values == null || values.isEmpty() ? 0 : values.hashCode();
    }

    private static int mapSize(@Nullable Map<?, ?> values) {
        return values == null ? 0 : values.size();
    }

    private static int jitterMs(@Nonnull Store<EntityStore> store) {
        return Math.floorMod(System.identityHashCode(store), SWEEP_JITTER_MS);
    }

    private static <T> void pruneInactiveKeys(@Nonnull Map<UUID, T> valuesByNpc,
                                              @Nonnull HashSet<UUID> activeNpcIds) {
        if (valuesByNpc.isEmpty()) {
            return;
        }
        if (activeNpcIds.isEmpty()) {
            valuesByNpc.clear();
            return;
        }
        for (UUID npcUuid : new ArrayList<>(valuesByNpc.keySet())) {
            if (npcUuid == null || activeNpcIds.contains(npcUuid)) {
                continue;
            }
            valuesByNpc.remove(npcUuid);
        }
    }

    record DynamicAttachmentFingerprint(
            @Nullable String roleId,
            @Nullable String displayName,
            @Nullable Boolean ownerPresent,
            @Nullable Boolean tamed,
            @Nullable String gender,
            @Nullable String lifeStage,
            @Nullable Double happiness,
            int needsHash,
            int needsCount,
            int traitsHash,
            int traitsCount,
            int commandStatesHash,
            int commandStatesCount,
            int permanentAttachmentsHash,
            int permanentAttachmentCount,
            int temporaryAttachmentsHash,
            int temporaryAttachmentCount,
            int storedAttachmentsHash,
            int storedAttachmentCount,
            int dynamicOverlayHash,
            int dynamicOverlayCount) {
    }

    private static final class TickState {
        private long nextSweepAtMs;
        private final Map<UUID, DynamicAttachmentFingerprint> lastFingerprintByNpc = new HashMap<>();
    }
}
