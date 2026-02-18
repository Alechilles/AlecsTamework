package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ClearTargetStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.FailurePolicy;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MembershipMode;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ModeMapping;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveToPositionStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.SetStateStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.SetTargetStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.TargetSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.TriggerHookStep;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Handles command-item linking and command dispatch.
 */
public final class CommandItemFeatureHandler {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private static final double DEFAULT_RAYCAST_DISTANCE = 64.0;

    private final CommandItemRegistry registry;

    public CommandItemFeatureHandler(CommandItemRegistry registry) {
        this.registry = registry;
    }

    // Handles a single command-item use.
    public boolean handleUse(Player player,
                             ItemStack itemStack,
                             Ref<EntityStore> targetRef,
                             String configIdOverride,
                             String commandIdOverride) {
        if (player == null || itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return false;
        }

        TwCommandItemConfig config = resolveConfig(itemStack.getItemId(), configIdOverride);
        if (config == null || !config.isEnabled()) {
            return false;
        }

        ToolResolution tool = ensureToolId(itemStack);
        ItemStack working = tool.stack;
        boolean updateHeldItem = tool.changed;
        if (tool.toolId == null || tool.toolId.isBlank()) {
            return false;
        }

        if (targetRef != null && config.isLinkEnabled() && config.isLinkUseTogglesMembership()) {
            LinkToggleResult link = tryToggleLink(player, store, targetRef, tool.toolId, config);
            if (link.toggled) {
                if (updateHeldItem) {
                    updateHeldItem(player, working);
                }
                sendMessage(player, (link.linked ? "Linked " : "Unlinked ") + link.npcName + ".");
                return true;
            }
        }

        if (shouldCycleCommand(commandIdOverride, store, playerRef)) {
            CommandSelectionResult selection = cycleSelectedCommand(config, working);
            if (selection.changed) {
                working = selection.stack;
                updateHeldItem = true;
            }
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            if (selection.command == null) {
                sendMessage(player, "No command is configured for this item.");
                return false;
            }
            sendMessage(player, "Selected command: " + resolveCommandLabel(selection.command) + ".");
            return true;
        }

        int cooldownMs = Math.max(0, config.getCooldownSeconds()) * 1000;
        if (isCooldownActive(working, cooldownMs)) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            sendMessage(player, "That command item is on cooldown.");
            return false;
        }

        CommandEntry command = resolveCommand(config, commandIdOverride, working);
        if (command == null) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            sendMessage(player, "No command is configured for this item.");
            return false;
        }

        Ref<EntityStore> commandTarget = targetRef != null && targetRef.isValid() && !targetRef.equals(playerRef)
                ? targetRef
                : null;
        Vector3d targetPosition = resolveTargetPosition(playerRef, store, config, command);
        Context context = new Context(player, playerRef, store, config, command, working.getItemId(), tool.toolId, commandTarget, targetPosition);

        List<Candidate> recipients = queryRecipients(context);
        if (recipients.isEmpty()) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            sendMessage(player, "No linked NPCs matched this command.");
            return false;
        }

        int affected = 0;
        for (Candidate candidate : recipients) {
            StepResult stepResult = executeCommand(context, candidate);
            if (stepResult.applied) {
                affected++;
            }
            if (stepResult.abortAll) {
                break;
            }
        }
        if (affected <= 0) {
            if (updateHeldItem) {
                updateHeldItem(player, working);
            }
            sendMessage(player, "No NPCs could execute that command.");
            return false;
        }

        if (cooldownMs > 0) {
            working = working.withMetadata(
                    TameworkMetadataKeys.COMMAND_COOLDOWN_UNTIL,
                    Codec.LONG,
                    System.currentTimeMillis() + cooldownMs
            );
            updateHeldItem = true;
        }
        if (updateHeldItem) {
            updateHeldItem(player, working);
        }
        sendMessage(player, "Command " + resolveCommandLabel(command) + " applied to " + affected + " NPC(s).");
        return true;
    }

    private boolean shouldCycleCommand(String commandIdOverride,
                                       Store<EntityStore> store,
                                       Ref<EntityStore> playerRef) {
        if (commandIdOverride != null && !commandIdOverride.isBlank()) {
            return false;
        }
        return isPlayerCrouching(store, playerRef);
    }

    private boolean isPlayerCrouching(Store<EntityStore> store, Ref<EntityStore> playerRef) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return false;
        }
        MovementStatesComponent movement = store.getComponent(playerRef, MovementStatesComponent.getComponentType());
        if (movement == null || movement.getMovementStates() == null) {
            return false;
        }
        MovementStates states = movement.getMovementStates();
        return states.crouching || states.forcedCrouching;
    }

    private CommandSelectionResult cycleSelectedCommand(TwCommandItemConfig config, ItemStack stack) {
        if (config == null || stack == null) {
            return CommandSelectionResult.none(stack);
        }
        String selectedId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING);
        CommandEntry next = config.findNextCommand(selectedId);
        if (next == null || next.getId() == null || next.getId().isBlank()) {
            return CommandSelectionResult.none(stack);
        }
        boolean changed = !commandIdEquals(next.getId(), selectedId);
        if (!changed) {
            return new CommandSelectionResult(stack, next, false);
        }
        ItemStack updated = stack.withMetadata(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING, next.getId());
        return new CommandSelectionResult(updated, next, true);
    }

    private boolean commandIdEquals(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private String resolveCommandLabel(CommandEntry command) {
        if (command == null) {
            return "Unknown";
        }
        if (command.getDisplayName() != null && !command.getDisplayName().isBlank()) {
            return command.getDisplayName();
        }
        if (command.getId() != null && !command.getId().isBlank()) {
            return command.getId();
        }
        return "Unknown";
    }

    private TwCommandItemConfig resolveConfig(String itemId, String configIdOverride) {
        if (configIdOverride != null && !configIdOverride.isBlank()
                && TwCommandItemConfig.getAssetMap() != null) {
            TwCommandItemConfig override = TwCommandItemConfig.getAssetMap().getAsset(configIdOverride);
            if (override != null) {
                return override;
            }
        }
        if (registry == null || itemId == null || itemId.isBlank()) {
            return null;
        }
        return registry.get(itemId);
    }

    private ToolResolution ensureToolId(ItemStack itemStack) {
        String toolId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        if (toolId != null && !toolId.isBlank()) {
            return new ToolResolution(itemStack, toolId, false);
        }
        String generated = UUID.randomUUID().toString();
        return new ToolResolution(
                itemStack.withMetadata(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING, generated),
                generated,
                true
        );
    }

    private CommandEntry resolveCommand(TwCommandItemConfig config, String commandIdOverride, ItemStack itemStack) {
        if (config == null) {
            return null;
        }
        CommandEntry direct = config.findCommandById(commandIdOverride);
        if (direct != null) {
            return direct;
        }
        if (itemStack != null) {
            String selectedId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING);
            CommandEntry selected = config.findCommandById(selectedId);
            if (selected != null) {
                return selected;
            }
        }
        return config.findDefaultCommand();
    }

    private Vector3d resolveTargetPosition(Ref<EntityStore> playerRef,
                                           Store<EntityStore> store,
                                           TwCommandItemConfig config,
                                           CommandEntry command) {
        if (!needsRaycast(command) || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        double distance = config != null && config.getRadius() > 0 ? config.getRadius() : DEFAULT_RAYCAST_DISTANCE;
        return TargetUtil.getTargetLocation(playerRef, blockId -> blockId != 0, distance, store);
    }

    private boolean needsRaycast(CommandEntry command) {
        if (command == null || command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (step instanceof MoveToPositionStep moveStep && moveStep.getSource() == MoveSource.RaycastHit) {
                return true;
            }
        }
        return false;
    }

    private List<Candidate> queryRecipients(Context context) {
        ArrayList<Candidate> out = new ArrayList<>();
        TransformComponent playerTransform = context.store.getComponent(context.playerRef, TransformComponent.getComponentType());
        Vector3d playerPos = playerTransform != null ? new Vector3d(playerTransform.getPosition()) : null;
        double radiusSq = context.config.getRadius() >= 0 ? context.config.getRadius() * context.config.getRadius() : -1;
        int maxTargets = Math.max(1, context.config.getMaxTargets());
        UUID playerUuid = context.player.getUuid();

        context.store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
                    continue;
                }
                if (!matchesMembership(context, npcRef, npc, playerUuid)) {
                    continue;
                }
                if (!passesOwnerAndTamed(context, npcRef, playerUuid)) {
                    continue;
                }
                if (!isRoleAllowed(resolveRoleId(npc), context.config)) {
                    continue;
                }
                TransformComponent npcTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                double distSq = 0;
                if (playerPos != null && npcTransform != null) {
                    Vector3d p = npcTransform.getPosition();
                    double dx = p.x - playerPos.x;
                    double dy = p.y - playerPos.y;
                    double dz = p.z - playerPos.z;
                    distSq = dx * dx + dy * dy + dz * dz;
                    if (radiusSq >= 0 && distSq > radiusSq) {
                        continue;
                    }
                } else if (radiusSq >= 0) {
                    continue;
                }
                out.add(new Candidate(npcRef, npc, distSq));
            }
        });
        out.sort(Comparator.comparingDouble(value -> value.distSq));
        if (out.size() > maxTargets) {
            return new ArrayList<>(out.subList(0, maxTargets));
        }
        return out;
    }

    private boolean matchesMembership(Context context, Ref<EntityStore> npcRef, NPCEntity npc, UUID playerUuid) {
        MembershipMode mode = context.config.getMembershipMode() != null
                ? context.config.getMembershipMode()
                : MembershipMode.LinkedOnly;
        boolean linked = isLinked(npcRef, playerUuid, context.toolId, context.store);
        boolean owner = isOwnedByPlayer(npcRef, playerUuid, context.store);
        boolean master = isMasterTargetedToPlayer(npc, context.playerRef);
        return switch (mode) {
            case OwnerScope -> owner;
            case MasterTarget -> master;
            case LinkedOrMasterTarget -> linked || master;
            case LinkedOnly -> linked;
        };
    }

    private boolean isLinked(Ref<EntityStore> npcRef, UUID playerUuid, String toolId, Store<EntityStore> store) {
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || toolId == null || toolId.isBlank()) {
            return false;
        }
        UUID ownerId = links.getOwnerId();
        if (ownerId != null && !ownerId.equals(playerUuid)) {
            return false;
        }
        return links.containsToolId(toolId);
    }

    private boolean isOwnedByPlayer(Ref<EntityStore> npcRef, UUID playerUuid, Store<EntityStore> store) {
        UUID ownerId = resolveOwnerId(npcRef, store);
        return ownerId != null && ownerId.equals(playerUuid);
    }

    private boolean passesOwnerAndTamed(Context context, Ref<EntityStore> npcRef, UUID playerUuid) {
        UUID ownerId = resolveOwnerId(npcRef, context.store);
        if (ownerId != null && !ownerId.equals(playerUuid)) {
            return false;
        }
        if (context.config.isRequireOwner() && ownerId == null) {
            return false;
        }
        if (context.config.isRequireTamed() && !TamedStateResolver.isTamed(npcRef, context.store)) {
            return false;
        }
        return true;
    }

    private UUID resolveOwnerId(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkOwnerComponent owner = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        return owner != null ? owner.getOwnerId() : null;
    }

    private boolean isMasterTargetedToPlayer(NPCEntity npc, Ref<EntityStore> playerRef) {
        if (npc == null || npc.getRole() == null || npc.getRole().getMarkedEntitySupport() == null) {
            return false;
        }
        try {
            Method method = npc.getRole().getMarkedEntitySupport().getClass().getMethod("getMarkedEntity", String.class);
            Object value = method.invoke(npc.getRole().getMarkedEntitySupport(), MASTER_TARGET_SLOT);
            if (!(value instanceof Ref<?> marked)) {
                return false;
            }
            return marked.isValid() && marked.equals(playerRef);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        if (npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
            return npc.getRoleName();
        }
        if (npc.getRoleIndex() >= 0 && NPCPlugin.get() != null) {
            return NPCPlugin.get().getName(npc.getRoleIndex());
        }
        return null;
    }

    private boolean isRoleAllowed(String roleId, TwCommandItemConfig config) {
        TwCommandItemConfig.AllowedRoles allowed = config.getAllowedRoles();
        if (allowed == null || allowed.getMode() == null) {
            return true;
        }
        return switch (allowed.getMode()) {
            case AllowAll -> true;
            case Allowlist -> contains(allowed.getAllowlist(), roleId);
            case Denylist -> !contains(allowed.getDenylist(), roleId);
        };
    }

    private boolean contains(String[] values, String expected) {
        if (values == null || values.length == 0 || expected == null || expected.isBlank()) {
            return false;
        }
        for (String value : values) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private StepResult executeCommand(Context context, Candidate candidate) {
        CommandStep[] steps = context.command.getSteps();
        if (steps == null || steps.length == 0) {
            return executeModeMapping(context, candidate);
        }
        boolean applied = false;
        for (CommandStep step : steps) {
            if (step == null) {
                continue;
            }
            boolean ok = applyStep(step, context, candidate);
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

    private StepResult executeModeMapping(Context context, Candidate candidate) {
        ModeMapping mode = context.command.getModeMapping();
        if (mode == null || mode.getState() == null || mode.getState().isBlank()) {
            return new StepResult(false, false);
        }
        boolean ok = applyState(candidate.ref, candidate.npc, context.store, mode.getState(), mode.getSubState());
        return new StepResult(ok, false);
    }

    private boolean applyStep(CommandStep step, Context context, Candidate candidate) {
        if (step instanceof SetStateStep stateStep) {
            return applyState(candidate.ref, candidate.npc, context.store, stateStep.getState(), stateStep.getSubState());
        }
        if (step instanceof SetTargetStep targetStep) {
            return applySetTarget(targetStep, context, candidate);
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
        if (step instanceof MoveToPositionStep moveStep) {
            return applyMove(moveStep, context, candidate);
        }
        if (step instanceof TriggerHookStep hookStep) {
            return applyHook(hookStep.getHookId(), context, candidate.ref);
        }
        return false;
    }

    private boolean applyState(Ref<EntityStore> npcRef,
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

    private boolean applySetTarget(SetTargetStep targetStep, Context context, Candidate candidate) {
        Role role = candidate.npc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return false;
        }
        String slot = targetStep.getTargetSlot();
        if (slot == null || slot.isBlank()) {
            slot = MASTER_TARGET_SLOT;
        }
        Ref<EntityStore> target = switch (targetStep.getSource() != null ? targetStep.getSource() : TargetSource.CrosshairTarget) {
            case OwnerPlayer -> context.playerRef;
            case StoredTarget -> readMarkedEntity(role, slot);
            case LastAttackTarget, CrosshairTarget -> context.commandTarget;
        };
        if (target == null || !target.isValid()) {
            return false;
        }
        role.getMarkedEntitySupport().setMarkedEntity(slot, target);
        return true;
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

    private boolean applyMove(MoveToPositionStep moveStep, Context context, Candidate candidate) {
        MoveSource source = moveStep.getSource() != null ? moveStep.getSource() : MoveSource.RaycastHit;
        if (source == MoveSource.RaycastHit && context.targetPosition == null) {
            return false;
        }
        if (source == MoveSource.OwnerPosition && candidate.npc.getRole() != null
                && candidate.npc.getRole().getMarkedEntitySupport() != null
                && context.playerRef != null
                && context.playerRef.isValid()) {
            candidate.npc.getRole().getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, context.playerRef);
        }
        return applyHook("Tamework.Command.MoveToPosition." + source.name(), context, candidate.ref);
    }

    private boolean applyHook(String hookId, Context context, Ref<EntityStore> npcRef) {
        if (hookId == null || hookId.isBlank() || npcRef == null || !npcRef.isValid()) {
            return false;
        }
        UUID playerId = context.player.getUuid();
        String playerName = context.player.getPlayerRef() != null ? context.player.getPlayerRef().getUsername() : null;
        context.store.putComponent(
                npcRef,
                TameworkHookComponent.getComponentType(),
                new TameworkHookComponent(hookId, playerId, playerName, context.itemId, System.currentTimeMillis(), true)
        );
        return true;
    }

    private LinkToggleResult tryToggleLink(Player player,
                                           Store<EntityStore> store,
                                           Ref<EntityStore> targetRef,
                                           String toolId,
                                           TwCommandItemConfig config) {
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null) {
            return LinkToggleResult.notToggled();
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return LinkToggleResult.notToggled();
        }
        UUID ownerId = resolveOwnerId(targetRef, store);
        if (ownerId != null && !ownerId.equals(playerId)) {
            return LinkToggleResult.notToggled();
        }
        if (config.isRequireOwner() && ownerId == null) {
            return LinkToggleResult.notToggled();
        }
        if (config.isRequireTamed() && !TamedStateResolver.isTamed(targetRef, store)) {
            return LinkToggleResult.notToggled();
        }
        if (!isRoleAllowed(resolveRoleId(npc), config)) {
            return LinkToggleResult.notToggled();
        }
        TameworkCommandLinksComponent current = store.getComponent(targetRef, TameworkCommandLinksComponent.getComponentType());
        if (current == null) {
            current = new TameworkCommandLinksComponent(playerId, new String[0]);
        }
        UUID linksOwner = current.getOwnerId();
        if (linksOwner != null && !linksOwner.equals(playerId)) {
            return LinkToggleResult.notToggled();
        }
        current.setOwnerId(playerId);
        boolean linked;
        TameworkCommandLinksComponent updated;
        if (current.containsToolId(toolId)) {
            updated = current.withToolIdRemoved(toolId);
            linked = false;
        } else {
            updated = current.withToolIdAdded(toolId);
            linked = true;
        }
        store.putComponent(targetRef, TameworkCommandLinksComponent.getComponentType(), updated);
        String name = npc.getLegacyDisplayName();
        if (name == null || name.isBlank()) {
            name = npc.getRoleName();
        }
        if (name == null || name.isBlank()) {
            name = "NPC";
        }
        return new LinkToggleResult(true, linked, name);
    }

    private boolean isCooldownActive(ItemStack stack, int cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long until = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_COOLDOWN_UNTIL, Codec.LONG);
        return until != null && until > System.currentTimeMillis();
    }

    private void sendMessage(Player player, String text) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        player.sendMessage(Message.raw(text));
    }

    private boolean updateHeldItem(Player player, ItemStack updated) {
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        byte slot = inventory.getActiveHotbarSlot();
        if (slot == Inventory.INACTIVE_SLOT_INDEX) {
            return false;
        }
        hotbar.setItemStackForSlot((short) slot, updated);
        inventory.markChanged();
        player.sendInventory();
        return true;
    }

    private static final class ToolResolution {
        private final ItemStack stack;
        private final String toolId;
        private final boolean changed;

        private ToolResolution(ItemStack stack, String toolId, boolean changed) {
            this.stack = stack;
            this.toolId = toolId;
            this.changed = changed;
        }
    }

    private static final class Context {
        private final Player player;
        private final Ref<EntityStore> playerRef;
        private final Store<EntityStore> store;
        private final TwCommandItemConfig config;
        private final CommandEntry command;
        private final String itemId;
        private final String toolId;
        private final Ref<EntityStore> commandTarget;
        private final Vector3d targetPosition;

        private Context(Player player,
                        Ref<EntityStore> playerRef,
                        Store<EntityStore> store,
                        TwCommandItemConfig config,
                        CommandEntry command,
                        String itemId,
                        String toolId,
                        Ref<EntityStore> commandTarget,
                        Vector3d targetPosition) {
            this.player = player;
            this.playerRef = playerRef;
            this.store = store;
            this.config = config;
            this.command = command;
            this.itemId = itemId;
            this.toolId = toolId;
            this.commandTarget = commandTarget;
            this.targetPosition = targetPosition;
        }
    }

    private static final class Candidate {
        private final Ref<EntityStore> ref;
        private final NPCEntity npc;
        private final double distSq;

        private Candidate(Ref<EntityStore> ref, NPCEntity npc, double distSq) {
            this.ref = ref;
            this.npc = npc;
            this.distSq = distSq;
        }
    }

    private static final class StepResult {
        private final boolean applied;
        private final boolean abortAll;

        private StepResult(boolean applied, boolean abortAll) {
            this.applied = applied;
            this.abortAll = abortAll;
        }
    }

    private static final class CommandSelectionResult {
        private final ItemStack stack;
        private final CommandEntry command;
        private final boolean changed;

        private CommandSelectionResult(ItemStack stack, CommandEntry command, boolean changed) {
            this.stack = stack;
            this.command = command;
            this.changed = changed;
        }

        private static CommandSelectionResult none(ItemStack stack) {
            return new CommandSelectionResult(stack, null, false);
        }
    }

    private static final class LinkToggleResult {
        private final boolean toggled;
        private final boolean linked;
        private final String npcName;

        private LinkToggleResult(boolean toggled, boolean linked, String npcName) {
            this.toggled = toggled;
            this.linked = linked;
            this.npcName = npcName;
        }

        private static LinkToggleResult notToggled() {
            return new LinkToggleResult(false, false, null);
        }
    }
}
