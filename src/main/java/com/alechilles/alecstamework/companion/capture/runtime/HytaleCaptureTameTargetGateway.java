package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CaptureTameLiveEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameLiveStateHasher;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MarkerAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.TargetProbe;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.Objects;
import javax.annotation.Nullable;

/** Exact NPC marker, live-state readback, and deterministic tame convergence. */
final class HytaleCaptureTameTargetGateway {
    private static final String TRANQUILIZER_EFFECT_ID =
            "Tw_Status_Tranquilized";

    private final World world;
    private final Store<EntityStore> store;
    private final CompanionCaptureRequest request;
    private final OperationEnvelope operation;

    HytaleCaptureTameTargetGateway(
            World world,
            Store<EntityStore> store,
            CompanionCaptureRequest request,
            OperationEnvelope operation
    ) {
        this.world = world;
        this.store = store;
        this.request = request;
        this.operation = operation;
    }

    TargetProbe probe() {
        ResolvedTarget target = resolveTarget();
        if (target == null) {
            return TargetProbe.absent();
        }
        if (!target.exactIdentity()) {
            return TargetProbe.conflict(null);
        }
        CaptureTameLiveStateHasher.State state = readState(target);
        if (state == null) {
            return TargetProbe.conflict(null);
        }
        TameworkProjectionIdentityComponent marker =
                target.projectionType() == null
                        ? null
                        : store.getComponent(
                                target.reference(),
                                target.projectionType()
                        );
        boolean rolePending =
                target.npc().getRole().isRoleChangeRequested();
        CaptureTameLiveEvidence evidence =
                request.tameAndLinkEvidence().live();
        if (marker == null) {
            return !rolePending
                    && expectedBusinessState(state, evidence)
                    && evidence.expectedStateHash().equals(
                            CaptureTameLiveStateHasher.hash(state)
                    )
                    ? TargetProbe.unchanged()
                    : TargetProbe.conflict(null);
        }
        if (!exactMarker(marker)) {
            return TargetProbe.conflict(null);
        }
        if (!rolePending
                && targetBusinessState(state, evidence)
                && evidence.targetStateHash().equals(
                        CaptureTameLiveStateHasher.hash(state)
                )) {
            return TargetProbe.target();
        }
        return expectedOrTargetRole(state.roleId(), evidence)
                ? TargetProbe.applying(rolePending)
                : TargetProbe.conflict(null);
    }

    boolean targetRoleResolvable() {
        NPCPlugin plugin = NPCPlugin.get();
        return plugin != null && plugin.getIndex(
                request.tameAndLinkEvidence().live().targetRoleId()
        ) >= 0;
    }

    MarkerAttempt installMarker() {
        TargetProbe before = probe();
        if (before.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING
                || before.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET) {
            return MarkerAttempt.exact();
        }
        if (before.status()
                != CompanionCaptureTameWorldAttempt.TargetStatus.UNCHANGED) {
            return MarkerAttempt.conflict(before.cause());
        }
        ResolvedTarget target = resolveTarget();
        if (target == null || target.projectionType() == null) {
            return MarkerAttempt.conflict(null);
        }
        try {
            store.putComponent(
                    target.reference(),
                    target.projectionType(),
                    expectedMarker()
            );
            return classifyMarkerReadback(null);
        } catch (RuntimeException | LinkageError failure) {
            return classifyMarkerReadback(failure);
        }
    }

    MutationAttempt converge() {
        TargetProbe before = probe();
        if (before.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET) {
            return MutationAttempt.applied();
        }
        if (before.status()
                != CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING) {
            return MutationAttempt.conflict(before.cause());
        }
        if (before.rolePending()) {
            return MutationAttempt.rolePending();
        }
        ResolvedTarget target = resolveTarget();
        if (target == null || !target.completeTypes()
                || !targetRoleResolvable()) {
            return MutationAttempt.retryable(null);
        }
        CaptureTameLiveEvidence evidence =
                request.tameAndLinkEvidence().live();
        try {
            putLiveAuthorities(target, evidence);
            detachSpawnAuthority(target);
            applyAuxiliaryState(target, evidence.targetRoleId());
            requestTargetRoleIfNeeded(target, evidence);
            return classifyMutationReadback(null);
        } catch (RuntimeException | LinkageError failure) {
            return classifyMutationReadback(failure);
        }
    }

    private MarkerAttempt classifyMarkerReadback(Throwable failure) {
        TargetProbe after = probe();
        return after.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING
                || after.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET
                ? MarkerAttempt.exact()
                : after.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.UNCHANGED
                ? MarkerAttempt.retryable(failure)
                : MarkerAttempt.conflict(failure);
    }

    private MutationAttempt classifyMutationReadback(Throwable failure) {
        TargetProbe after = probe();
        return after.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET
                ? MutationAttempt.applied()
                : after.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING
                ? failure == null
                ? MutationAttempt.rolePending()
                : MutationAttempt.retryable(failure)
                : MutationAttempt.conflict(failure);
    }

    private void putLiveAuthorities(
            ResolvedTarget target,
            CaptureTameLiveEvidence evidence
    ) {
        store.putComponent(
                target.reference(),
                target.tamedType(),
                new TameworkTamedComponent(true)
        );
        store.putComponent(
                target.reference(),
                target.ownerType(),
                new TameworkOwnerComponent(
                        evidence.targetOwnerId().value(),
                        evidence.targetOwnerName()
                )
        );
        store.putComponent(
                target.reference(),
                target.linksType(),
                new TameworkCommandLinksComponent(
                        evidence.targetOwnerId().value(),
                        new String[]{rosterLinkId(evidence)}
                )
        );
    }

    private void detachSpawnAuthority(ResolvedTarget target) {
        removeIfPresent(target.reference(), target.markerType());
        removeIfPresent(target.reference(), target.beaconType());
        target.npc().updateSpawnTrackingState(false);
        target.npc().setSpawnConfiguration(
                AssetMapWithIndexes.NOT_FOUND
        );
        target.npc().setEnvironment(AssetMapWithIndexes.NOT_FOUND);
        target.npc().setSpawnRoleIndex(AssetMapWithIndexes.NOT_FOUND);
    }

    private void applyAuxiliaryState(
            ResolvedTarget target,
            String targetRoleId
    ) {
        CompanionProgressionBootstrapService.ensureProgressionComponents(
                target.reference(), store, targetRoleId
        );
        TameworkEntityEffectService.removeEffect(
                target.reference(),
                TRANQUILIZER_EFFECT_ID,
                store
        );
    }

    private void requestTargetRoleIfNeeded(
            ResolvedTarget target,
            CaptureTameLiveEvidence evidence
    ) {
        String currentRole = roleId(target.npc());
        if (!evidence.expectedRoleId().equals(currentRole)
                || evidence.targetRoleId().equals(currentRole)) {
            return;
        }
        RoleChangeSystem.requestRoleChange(
                target.reference(),
                target.npc().getRole(),
                NPCPlugin.get().getIndex(evidence.targetRoleId()),
                false,
                null,
                null,
                true,
                store
        );
    }

    @Nullable
    private CaptureTameLiveStateHasher.State readState(
            ResolvedTarget target
    ) {
        return HytaleCaptureTameLiveStateFreezer.freeze(
                target.reference(),
                store,
                request.targetAlias()
        );
    }

    private boolean expectedBusinessState(
            CaptureTameLiveStateHasher.State state,
            CaptureTameLiveEvidence evidence
    ) {
        return evidence.expectedRoleId().equals(state.roleId())
                && Objects.equals(
                        evidence.expectedOwnerId(), state.ownerId()
                )
                && evidence.expectedTamed() == state.tamed();
    }

    private boolean targetBusinessState(
            CaptureTameLiveStateHasher.State state,
            CaptureTameLiveEvidence evidence
    ) {
        return evidence.targetRoleId().equals(state.roleId())
                && evidence.targetOwnerId().equals(state.ownerId())
                && evidence.targetOwnerName().equals(state.ownerName())
                && state.tamed();
    }

    private boolean expectedOrTargetRole(
            String roleId,
            CaptureTameLiveEvidence evidence
    ) {
        return evidence.expectedRoleId().equals(roleId)
                || evidence.targetRoleId().equals(roleId);
    }

    private boolean exactMarker(
            @Nullable TameworkProjectionIdentityComponent marker
    ) {
        if (marker == null) {
            return false;
        }
        var evidence = request.tameAndLinkEvidence();
        return marker.matches(
                TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                operation.operationId().toString(),
                request.profileId().toString()
        )
                && evidence.rosterMembership().familyKey().familyId()
                .equals(marker.getSlotKey())
                && request.targetAlias().value().equals(
                        marker.getSourceNpcUuid()
                )
                && evidence.finalLifecycle()
                .lastReconciledGeneration().value()
                == marker.getGeneration();
    }

    private TameworkProjectionIdentityComponent expectedMarker() {
        var evidence = request.tameAndLinkEvidence();
        return new TameworkProjectionIdentityComponent(
                request.profileId().toString(),
                operation.operationId().toString(),
                TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                evidence.rosterMembership().familyKey().familyId(),
                request.targetAlias().value(),
                evidence.finalLifecycle()
                        .lastReconciledGeneration().value()
        );
    }

    private String rosterLinkId(CaptureTameLiveEvidence evidence) {
        return "roster:" + evidence.targetOwnerId() + ":"
                + evidence.commandAccess().commandFamilyId();
    }

    @Nullable
    private ResolvedTarget resolveTarget() {
        Ref<EntityStore> reference =
                world.getEntityRef(request.targetAlias().value());
        if (reference == null || !reference.isValid()) {
            return null;
        }
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        if (uuidType == null || npcType == null) {
            return new ResolvedTarget(
                    reference, null, null,
                    null, null, null, null, null, null
            );
        }
        UUIDComponent identity = store.getComponent(reference, uuidType);
        NPCEntity npc = store.getComponent(reference, npcType);
        boolean exact = identity != null && npc != null
                && request.targetAlias().value().equals(
                        identity.getUuid()
                )
                && npc.getRole() != null;
        return new ResolvedTarget(
                reference,
                npc,
                exact,
                TameworkOwnerComponent.getComponentType(),
                TameworkTamedComponent.getComponentType(),
                TameworkCommandLinksComponent.getComponentType(),
                TameworkProjectionIdentityComponent.getComponentType(),
                componentType(SpawnMarkerReference::getComponentType),
                componentType(SpawnBeaconReference::getComponentType)
        );
    }

    private String roleId(NPCEntity npc) {
        String roleId = npc == null || npc.getRole() == null
                ? null
                : npc.getRole().getRoleName();
        if (roleId == null || roleId.isBlank()) {
            throw new IllegalStateException(
                    "Live NPC role ID is unavailable"
            );
        }
        return roleId.trim();
    }

    private <T extends Component<EntityStore>> boolean present(
            Ref<EntityStore> reference,
            ComponentType<EntityStore, T> type
    ) {
        return type != null && store.getComponent(reference, type) != null;
    }

    private <T extends Component<EntityStore>> void removeIfPresent(
            Ref<EntityStore> reference,
            ComponentType<EntityStore, T> type
    ) {
        if (present(reference, type)) {
            store.tryRemoveComponent(reference, type);
        }
    }

    @Nullable
    private static <T extends Component<EntityStore>>
    ComponentType<EntityStore, T> componentType(
            ComponentTypeSupplier<T> supplier
    ) {
        try {
            return supplier.get();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface ComponentTypeSupplier<
            T extends Component<EntityStore>> {
        ComponentType<EntityStore, T> get();
    }

    private record ResolvedTarget(
            Ref<EntityStore> reference,
            @Nullable NPCEntity npc,
            @Nullable Boolean exactIdentityValue,
            ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            ComponentType<EntityStore, TameworkTamedComponent> tamedType,
            ComponentType<EntityStore, TameworkCommandLinksComponent>
                    linksType,
            ComponentType<
                    EntityStore,
                    TameworkProjectionIdentityComponent> projectionType,
            ComponentType<EntityStore, SpawnMarkerReference> markerType,
            ComponentType<EntityStore, SpawnBeaconReference> beaconType
    ) {
        boolean exactIdentity() {
            return Boolean.TRUE.equals(exactIdentityValue);
        }

        boolean completeTypes() {
            return exactIdentity()
                    && ownerType != null
                    && tamedType != null
                    && linksType != null
                    && projectionType != null
                    && markerType != null
                    && beaconType != null;
        }
    }
}
