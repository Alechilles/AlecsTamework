package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ClearCombatStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ClearTargetStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.FailurePolicy;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ModeMapping;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveToPositionStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.SetStateStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.SetTargetStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.StoreHomeStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.StoreSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.TargetSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.TriggerHookStep;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.lang.reflect.Method;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Executes command steps against selected NPC recipients.
 */
final class CommandStepExecutionService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";

    private final CommandNpcRelocationService relocationService;
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandNpcNameResolver npcNameResolver;

    CommandStepExecutionService(CommandNpcRelocationService relocationService,
                                CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                CommandNpcNameResolver npcNameResolver) {
        this.relocationService = relocationService;
        this.linkedNpcRecordStore = linkedNpcRecordStore != null ? linkedNpcRecordStore : new CommandLinkedNpcRecordStore();
        this.npcNameResolver = npcNameResolver != null ? npcNameResolver : new CommandNpcNameResolver();
    }

    StepResult executeCommand(Context context, Candidate candidate) {
        TwCompanionConfig.EffectiveSettings companionSettings = resolveCompanionSettings(context, candidate);
        CommandStep[] steps = context.command.getSteps();
        if (steps == null || steps.length == 0) {
            return executeModeMapping(context, candidate);
        }
        boolean applied = false;
        for (CommandStep step : steps) {
            if (step == null) {
                continue;
            }
            boolean ok = applyStep(step, context, candidate, companionSettings);
            if (ok) {
                applied = true;
                continue;
            }
            if (step.isOptional()) {
                continue;
            }
            FailurePolicy policy = step.getFailurePolicy() != null ? step.getFailurePolicy() : FailurePolicy.Continue;
            if (policy == FailurePolicy.AbortAll) {
                return new StepResult(applied, true);
            }
            if (policy == FailurePolicy.AbortCommandForNpc) {
                return new StepResult(applied, false);
            }
        }
        return new StepResult(applied, false);
    }

    boolean applyState(Ref<EntityStore> npcRef,
                       NPCEntity npc,
                       Store<EntityStore> store,
                       String state,
                       String subState) {
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return false;
        }
        if (state == null || state.isBlank()) {
            return false;
        }
        StateSupport support = npc.getRole().getStateSupport();
        String resolvedSub = subState;
        if (support.getStateHelper() != null) {
            int stateIndex = support.getStateHelper().getStateIndex(state);
            if (stateIndex == StateSupport.NO_STATE) {
                return false;
            }
            if (resolvedSub == null || resolvedSub.isBlank()) {
                resolvedSub = support.getStateHelper().getDefaultSubState();
            } else if (support.getStateHelper().getSubStateIndex(stateIndex, resolvedSub) == StateSupport.NO_STATE) {
                return false;
            }
        }
        support.setState(npcRef, state, resolvedSub == null ? "" : resolvedSub, store);
        return true;
    }

    RelocationState resolveRelocationState(CommandEntry command, boolean returnHome, boolean recall) {
        String state = null;
        String subState = null;
        if (command != null && command.getSteps() != null) {
            for (CommandStep step : command.getSteps()) {
                if (step instanceof SetStateStep setStateStep) {
                    if (setStateStep.getState() == null || setStateStep.getState().isBlank()) {
                        continue;
                    }
                    state = setStateStep.getState();
                    subState = setStateStep.getSubState();
                    continue;
                }
                if (step instanceof ClearCombatStep clearCombatStep) {
                    if (clearCombatStep.getState() == null || clearCombatStep.getState().isBlank()) {
                        continue;
                    }
                    state = clearCombatStep.getState();
                    subState = clearCombatStep.getSubState();
                }
            }
        }
        if ((state == null || state.isBlank()) && returnHome) {
            state = "Hold";
        }
        if ((state == null || state.isBlank()) && recall) {
            state = "Idle";
        }
        return new RelocationState(state, subState);
    }

    private StepResult executeModeMapping(Context context, Candidate candidate) {
        ModeMapping mode = context.command.getModeMapping();
        if (mode == null || mode.getState() == null || mode.getState().isBlank()) {
            return new StepResult(false, false);
        }
        boolean ok = applyState(candidate.ref, candidate.npc, context.store, mode.getState(), mode.getSubState());
        return new StepResult(ok, false);
    }

    private boolean applyStep(CommandStep step,
                              Context context,
                              Candidate candidate,
                              TwCompanionConfig.EffectiveSettings companionSettings) {
        if (step instanceof SetStateStep stateStep) {
            return applyState(candidate.ref, candidate.npc, context.store, stateStep.getState(), stateStep.getSubState());
        }
        if (step instanceof SetTargetStep targetStep) {
            return applySetTarget(targetStep, context, candidate, companionSettings);
        }
        if (step instanceof ClearTargetStep clearStep) {
            String slot = clearStep.getTargetSlot();
            if (slot == null || slot.isBlank()) {
                slot = MASTER_TARGET_SLOT;
            }
            Role role = candidate.npc.getRole();
            if (role == null || role.getMarkedEntitySupport() == null) {
                return false;
            }
            role.getMarkedEntitySupport().setMarkedEntity(slot, null);
            return true;
        }
        if (step instanceof ClearCombatStep clearCombatStep) {
            return applyClearCombat(clearCombatStep, context, candidate);
        }
        if (step instanceof MoveToPositionStep moveStep) {
            return applyMove(moveStep, context, candidate, companionSettings);
        }
        if (step instanceof StoreHomeStep storeHomeStep) {
            return applyStoreHome(storeHomeStep, context, candidate);
        }
        if (step instanceof TriggerHookStep hookStep) {
            return applyHook(hookStep.getHookId(), context, candidate.ref);
        }
        return false;
    }

    private boolean applySetTarget(SetTargetStep targetStep,
                                   Context context,
                                   Candidate candidate,
                                   TwCompanionConfig.EffectiveSettings companionSettings) {
        Role role = candidate.npc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return false;
        }
        String slot = targetStep.getTargetSlot();
        if (slot == null || slot.isBlank()) {
            slot = MASTER_TARGET_SLOT;
        }
        TargetSource source = targetStep.getSource() != null ? targetStep.getSource() : TargetSource.CrosshairTarget;
        Ref<EntityStore> target = switch (source) {
            case OwnerPlayer -> context.playerRef;
            case StoredTarget -> readMarkedEntity(role, slot);
            case LastAttackTarget, CrosshairTarget -> context.commandTarget;
        };
        if (target == null || !target.isValid()) {
            return false;
        }
        if ((source == TargetSource.CrosshairTarget || source == TargetSource.LastAttackTarget)
                && !isHostileTargetAllowed(target, context, candidate, companionSettings)) {
            return false;
        }
        role.getMarkedEntitySupport().setMarkedEntity(slot, target);
        return true;
    }

    private boolean isHostileTargetAllowed(Ref<EntityStore> target,
                                           Context context,
                                           Candidate candidate,
                                           TwCompanionConfig.EffectiveSettings companionSettings) {
        if (target == null || !target.isValid()) {
            return false;
        }
        UUID commanderId = context.player != null ? context.player.getUuid() : null;
        TameworkOwnerComponent targetOwner = context.store.getComponent(target, TameworkOwnerComponent.getComponentType());
        UUID targetOwnerId = targetOwner != null ? targetOwner.getOwnerId() : null;
        boolean targetOwnedByCommander = commanderId != null && targetOwnerId != null && commanderId.equals(targetOwnerId);
        Player targetPlayer = context.store.getComponent(target, Player.getComponentType());
        boolean targetIsPlayer = targetPlayer != null;
        boolean playerTargetingAllowed = !targetIsPlayer || isPlayerTargetAllowed(context.player.getWorld());
        boolean targetPlayerSpawnProtected = targetIsPlayer && targetPlayer.hasSpawnProtection();
        return CommandTargetPermission.isAllowed(
                target.equals(context.playerRef),
                target.equals(candidate.ref),
                targetOwnedByCommander,
                targetIsPlayer,
                playerTargetingAllowed,
                targetPlayerSpawnProtected,
                targetOwnerId != null,
                companionSettings.isBlockAllPlayerDamageIfOwned(),
                companionSettings.isInvulnerableIfOwned()
        );
    }

    private boolean isPlayerTargetAllowed(World world) {
        if (world == null || world.getWorldConfig() == null) {
            return true;
        }
        return world.getWorldConfig().isPvpEnabled();
    }

    @SuppressWarnings("unchecked")
    private Ref<EntityStore> readMarkedEntity(Role role, String slot) {
        if (role == null || role.getMarkedEntitySupport() == null) {
            return null;
        }
        try {
            Method method = role.getMarkedEntitySupport().getClass().getMethod("getMarkedEntity", String.class);
            Object value = method.invoke(role.getMarkedEntitySupport(), slot);
            if (value instanceof Ref<?>) {
                return (Ref<EntityStore>) value;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private boolean applyMove(MoveToPositionStep moveStep,
                              Context context,
                              Candidate candidate,
                              TwCompanionConfig.EffectiveSettings companionSettings) {
        MoveSource source = moveStep.getSource() != null ? moveStep.getSource() : MoveSource.RaycastHit;
        Vector3d targetPosition = null;
        if (source == MoveSource.RaycastHit) {
            targetPosition = context.raycastPosition;
            if (targetPosition == null) {
                return false;
            }
        } else if (source == MoveSource.StoredHome) {
            targetPosition = readStoredHomePosition(candidate.ref, context.store);
            if (targetPosition == null) {
                return false;
            }
        } else if (source == MoveSource.OwnerPosition && candidate.npc.getRole() != null
                && candidate.npc.getRole().getMarkedEntitySupport() != null
                && context.playerRef != null
                && context.playerRef.isValid()) {
            candidate.npc.getRole().getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, context.playerRef);
        }
        if (source == MoveSource.StoredHome && targetPosition != null) {
            TransformComponent npcTransform = context.store.getComponent(candidate.ref, TransformComponent.getComponentType());
            Vector3d start = npcTransform != null ? new Vector3d(npcTransform.getPosition()) : null;
            double returnHomeTeleportDistance = resolvePositiveDouble(
                    companionSettings.getReturnHomeTeleportDistance(),
                    context.returnHomeTeleportDistance
            );
            double returnHomePathDistanceBeforeTeleport = resolvePositiveDouble(
                    companionSettings.getReturnHomePathDistanceBeforeTeleport(),
                    context.returnHomePathDistanceBeforeTeleport
            );
            long returnHomeTeleportDelayMs = resolvePositiveLong(
                    companionSettings.getReturnHomeTeleportDelayMs(),
                    context.returnHomeTeleportDelayMs
            );
            if (CommandTravelSettings.isRecallTeleportingEnabled()
                    && start != null
                    && start.distance(targetPosition) > returnHomeTeleportDistance) {
                Vector3d intermediate = computeIntermediatePoint(start, targetPosition, returnHomePathDistanceBeforeTeleport);
                RelocationState postRelocationState = resolveRelocationState(context.command, true, false);
                World world = context.player != null ? context.player.getWorld() : null;
                UUID ownerUuid = context.player != null ? context.player.getUuid() : null;
                UUID npcUuid = candidate.npc != null ? candidate.npc.getUuid() : null;
                if (world != null && npcUuid != null && relocationService != null) {
                    relocationService.queueRelocation(
                            world,
                            npcUuid,
                            targetPosition,
                            ownerUuid,
                            false,
                            true,
                            postRelocationState.state,
                            postRelocationState.subState,
                            returnHomeTeleportDelayMs,
                            start,
                            targetPosition
                    );
                }
                return applyHook("Tamework.Command.MoveToPosition." + source.name(), context, candidate.ref, intermediate);
            }
        }
        return applyHook("Tamework.Command.MoveToPosition." + source.name(), context, candidate.ref, targetPosition);
    }

    private Vector3d readStoredHomePosition(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || !links.hasHome()) {
            return null;
        }
        return links.getHomePosition();
    }

    private boolean applyStoreHome(StoreHomeStep step, Context context, Candidate candidate) {
        if (step == null || context == null || candidate == null || candidate.ref == null || candidate.npc == null) {
            return false;
        }
        Vector3d home = null;
        StoreSource source = step.getSource() != null ? step.getSource() : StoreSource.RaycastHit;
        if (source == StoreSource.RaycastHit) {
            home = context.raycastPosition;
        } else if (source == StoreSource.OwnerPosition) {
            TransformComponent playerTransform = context.store.getComponent(context.playerRef, TransformComponent.getComponentType());
            if (playerTransform != null) {
                home = new Vector3d(playerTransform.getPosition());
            }
        }
        if (home == null) {
            return false;
        }
        TameworkCommandLinksComponent links = context.store.getComponent(
                candidate.ref,
                TameworkCommandLinksComponent.getComponentType()
        );
        if (links == null) {
            links = new TameworkCommandLinksComponent();
        }
        if (links.getOwnerId() == null && context.player != null && context.player.getUuid() != null) {
            links.setOwnerId(context.player.getUuid());
        }
        links.setHomePosition(home);
        context.store.putComponent(candidate.ref, TameworkCommandLinksComponent.getComponentType(), links);

        if (context.workingItem != null && !context.workingItem.isEmpty() && candidate.npc.getUuid() != null) {
            TransformComponent transform = context.store.getComponent(candidate.ref, TransformComponent.getComponentType());
            Vector3d currentPosition = transform != null ? new Vector3d(transform.getPosition()) : null;
            ItemStack updated = linkedNpcRecordStore.upsert(
                    context.workingItem,
                    candidate.npc.getUuid(),
                    currentPosition,
                    resolveWorldName(context),
                    home,
                    npcNameResolver.resolveNpcDisplayNameFromComponents(candidate.ref, context.store),
                    npcNameResolver.resolveNpcNameKey(candidate.npc),
                    npcNameResolver.resolveNpcRoleId(candidate.npc),
                    null,
                    null
            );
            if (updated != context.workingItem) {
                context.workingItem = updated;
                context.itemChanged = true;
            }
        }
        return true;
    }

    @Nullable
    private String resolveWorldName(Context context) {
        World world = context != null && context.player != null ? context.player.getWorld() : null;
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        return world.getName();
    }

    private boolean applyClearCombat(ClearCombatStep step, Context context, Candidate candidate) {
        if (step == null || candidate == null || candidate.npc == null) {
            return false;
        }
        boolean applied = false;
        Role role = candidate.npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            String[] slots = step.getTargetSlots();
            if (slots == null || slots.length == 0) {
                slots = new String[] { "LockedTarget" };
            }
            for (String slot : slots) {
                if (slot == null || slot.isBlank()) {
                    continue;
                }
                role.getMarkedEntitySupport().setMarkedEntity(slot, null);
                applied = true;
            }
            if (step.isAssignOwnerAsMasterTarget()
                    && context.playerRef != null
                    && context.playerRef.isValid()) {
                role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, context.playerRef);
                applied = true;
            }
        }
        String state = step.getState();
        if (state == null || state.isBlank()) {
            return applied;
        }
        boolean stateApplied = applyState(candidate.ref, candidate.npc, context.store, state, step.getSubState());
        return applied || stateApplied;
    }

    private boolean applyHook(String hookId, Context context, Ref<EntityStore> npcRef) {
        return applyHook(hookId, context, npcRef, null);
    }

    private boolean applyHook(String hookId,
                              Context context,
                              Ref<EntityStore> npcRef,
                              Vector3d targetPosition) {
        if (hookId == null || hookId.isBlank() || npcRef == null || !npcRef.isValid()) {
            return false;
        }
        UUID playerId = context.player.getUuid();
        String playerName = context.player.getPlayerRef() != null ? context.player.getPlayerRef().getUsername() : null;
        context.store.putComponent(
                npcRef,
                TameworkHookComponent.getComponentType(),
                new TameworkHookComponent(
                        hookId,
                        playerId,
                        playerName,
                        context.itemId,
                        System.currentTimeMillis(),
                        true,
                        targetPosition
                )
        );
        return true;
    }

    private TwCompanionConfig.EffectiveSettings resolveCompanionSettings(Context context, Candidate candidate) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(candidate.ref, context.store);
        if ((roleId == null || roleId.isBlank()) && candidate.npc != null) {
            roleId = candidate.npc.getRoleName();
        }
        return TwCompanionConfig.resolveEffectiveForRole(roleId);
    }

    private double resolvePositiveDouble(double configured, double fallback) {
        return configured > 0.0 ? configured : fallback;
    }

    private long resolvePositiveLong(long configured, long fallback) {
        return configured > 0L ? configured : fallback;
    }

    private Vector3d computeIntermediatePoint(Vector3d from, Vector3d to, double distance) {
        if (from == null || to == null || distance <= 0.0) {
            return from != null ? new Vector3d(from) : null;
        }
        Vector3d delta = new Vector3d(to.x - from.x, to.y - from.y, to.z - from.z);
        double len = Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z);
        if (len <= 0.0001) {
            return new Vector3d(from);
        }
        double factor = Math.min(1.0, distance / len);
        return new Vector3d(
                from.x + delta.x * factor,
                from.y + delta.y * factor,
                from.z + delta.z * factor
        );
    }
}
