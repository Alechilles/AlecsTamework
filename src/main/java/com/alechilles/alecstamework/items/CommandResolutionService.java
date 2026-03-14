package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.ClearCombatStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveToPositionStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.SetTargetStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.StoreHomeStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.StoreSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.TargetSource;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Resolves command-item config and command selection details.
 *
 * <p>This service also centralizes command classification and targeting/raycast checks so the
 * handler remains focused on orchestration.
 */
final class CommandResolutionService {
    private final CommandItemRegistry registry;
    private final double defaultRaycastDistance;

    CommandResolutionService(CommandItemRegistry registry, double defaultRaycastDistance) {
        this.registry = registry;
        this.defaultRaycastDistance = defaultRaycastDistance;
    }

    TwCommandItemConfig resolveConfig(String itemId, String configIdOverride) {
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

    CommandEntry resolveCommand(TwCommandItemConfig config, String commandIdOverride, ItemStack itemStack) {
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

    CommandEntry resolvePanelRecallCommand(TwCommandItemConfig config, ItemStack itemStack) {
        if (config == null) {
            return null;
        }
        CommandEntry direct = config.findCommandById("Recall");
        if (direct != null) {
            return direct;
        }
        CommandEntry[] commands = config.getCommandList();
        if (commands != null) {
            for (CommandEntry entry : commands) {
                if (isRecallCommand(entry)) {
                    return entry;
                }
            }
        }
        CommandEntry fallback = resolveCommand(config, null, itemStack);
        return isRecallCommand(fallback) ? fallback : null;
    }

    CommandEntry resolvePanelReturnHomeCommand(TwCommandItemConfig config, ItemStack itemStack) {
        if (config == null) {
            return null;
        }
        CommandEntry direct = config.findCommandById("ReturnHome");
        if (direct != null) {
            return direct;
        }
        CommandEntry[] commands = config.getCommandList();
        if (commands != null) {
            for (CommandEntry entry : commands) {
                if (isReturnHomeCommand(entry)) {
                    return entry;
                }
            }
        }
        CommandEntry fallback = resolveCommand(config, null, itemStack);
        if (isReturnHomeCommand(fallback)) {
            return fallback;
        }
        return TwCommandItemConfig.createBuiltInReturnHomeCommand();
    }

    Ref<EntityStore> resolveCommandTarget(Ref<EntityStore> playerRef,
                                          Store<EntityStore> store,
                                          TwCommandItemConfig config,
                                          CommandEntry command,
                                          Ref<EntityStore> explicitTarget) {
        if (playerRef == null || !playerRef.isValid() || store == null) {
            return null;
        }
        if (explicitTarget != null && explicitTarget.isValid() && !explicitTarget.equals(playerRef)) {
            return explicitTarget;
        }
        if (!needsEntityTarget(command)) {
            return null;
        }
        float radius = resolveTargetEntityRadius(config);
        Ref<EntityStore> raycastTarget = TargetUtil.getTargetEntity(playerRef, radius, store);
        if (raycastTarget == null || !raycastTarget.isValid() || raycastTarget.equals(playerRef)) {
            return null;
        }
        return raycastTarget;
    }

    Vector3d resolveRaycastPosition(Ref<EntityStore> playerRef,
                                    Store<EntityStore> store,
                                    TwCommandItemConfig config,
                                    CommandEntry command) {
        if (!needsRaycast(command) || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        double distance = config != null && config.getRadius() > 0 ? config.getRadius() : defaultRaycastDistance;
        return TargetUtil.getTargetLocation(playerRef, blockId -> blockId != 0, distance, store);
    }

    boolean isReturnHomeCommand(CommandEntry command) {
        if (command == null || command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (!(step instanceof MoveToPositionStep moveStep)) {
                continue;
            }
            MoveSource source = moveStep.getSource() != null ? moveStep.getSource() : MoveSource.RaycastHit;
            if (source == MoveSource.StoredHome) {
                return true;
            }
        }
        return false;
    }

    boolean isRecallCommand(CommandEntry command) {
        if (command == null) {
            return false;
        }
        if (command.getId() != null && "recall".equalsIgnoreCase(command.getId().trim())) {
            return true;
        }
        if (command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (step instanceof ClearCombatStep clearCombatStep && clearCombatStep.isAssignOwnerAsMasterTarget()) {
                return true;
            }
        }
        return false;
    }

    private boolean needsEntityTarget(CommandEntry command) {
        if (command == null || command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (step instanceof SetTargetStep targetStep) {
                TargetSource source = targetStep.getSource() != null
                        ? targetStep.getSource()
                        : TargetSource.CrosshairTarget;
                if (source == TargetSource.CrosshairTarget || source == TargetSource.LastAttackTarget) {
                    return true;
                }
            }
        }
        return false;
    }

    private float resolveTargetEntityRadius(TwCommandItemConfig config) {
        double distance = config != null && config.getRadius() > 0 ? config.getRadius() : defaultRaycastDistance;
        distance = Math.max(1.0, Math.min(distance, Float.MAX_VALUE));
        return (float) distance;
    }

    private boolean needsRaycast(CommandEntry command) {
        if (command == null || command.getSteps() == null) {
            return false;
        }
        for (CommandStep step : command.getSteps()) {
            if (step instanceof MoveToPositionStep moveStep && moveStep.getSource() == MoveSource.RaycastHit) {
                return true;
            }
            if (step instanceof StoreHomeStep storeHomeStep
                    && storeHomeStep.getSource() == StoreSource.RaycastHit) {
                return true;
            }
        }
        return false;
    }
}
