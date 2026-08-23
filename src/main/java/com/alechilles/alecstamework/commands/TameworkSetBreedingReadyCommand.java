package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.npc.actions.BreedingCooldownResetService;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.HappinessConfigResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.commands.NPCMultiSelectCommandBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import javax.annotation.Nonnull;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;

/**
 * Sets breeding readiness for NPCs selected with the standard NPC debug selectors.
 */
public final class TameworkSetBreedingReadyCommand extends NPCMultiSelectCommandBase {
    private static final double READY_HAPPINESS = 100.0;
    private final OptionalArg<String> modeArg = withOptionalArg(
            "mode", "Readiness mode: true, false, or toggle.", STRING
    ).suggest((sender, entered, parameters, suggestions) -> {
        suggestions.suggest("true");
        suggestions.suggest("false");
        suggestions.suggest("toggle");
    });
    private final BreedingCooldownResetService cooldownResetService = new BreedingCooldownResetService();

    public TameworkSetBreedingReadyCommand() {
        super("breedingready", "Set breeding readiness for selected NPCs.");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        ReadyMode mode = parseMode(modeArg.provided(context) ? modeArg.get(context) : "true");
        if (mode == null) {
            context.sendMessage(Message.raw("--mode must be true, false, or toggle."));
            return;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            context.sendMessage(Message.raw("Breeding component is not available."));
            return;
        }
        applyReadyHappiness(npcRef, store);
        MutationResult result = applyMutation(npcRef, npc, store, breedingType, mode);
        sendResult(context, npc, result);
    }

    private static void applyReadyHappiness(@Nonnull Ref<EntityStore> npcRef,
                                            @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType =
                TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return;
        }
        TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
        TwHappinessConfig config = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        if (!HappinessConfigResolver.isRuntimeEnabled(config)) {
            return;
        }
        double min = config.getValues().getMin();
        double max = config.getValues().getMax();
        double value = Math.max(Math.min(READY_HAPPINESS, Math.max(min, max)), Math.min(min, max));
        long now = System.currentTimeMillis();
        TameworkHappinessComponent next = happiness == null
                ? new TameworkHappinessComponent(config.getId(), value, now)
                : happiness;
        next.setValue(value);
        next.setLastUpdateMs(now);
        if (next.getConfigId() == null || next.getConfigId().isBlank()) {
            next.setConfigId(config.getId());
        }
        store.putComponent(npcRef, happinessType, next);
    }

    @Nonnull
    private MutationResult applyMutation(@Nonnull Ref<EntityStore> npcRef,
                                         @Nonnull NPCEntity npc,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull ComponentType<EntityStore, TameworkBreedingComponent> breedingType,
                                         @Nonnull ReadyMode mode) {
        CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null || !breeding.isEnabled()) {
            return MutationResult.skipped();
        }
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        boolean readyBefore = breeding.isReady() && !breeding.isCooldownActive(now);
        boolean nextReady = mode == ReadyMode.TOGGLE ? !readyBefore : mode == ReadyMode.TRUE;
        if (nextReady) {
            if (!cooldownResetService.forceReady(npcRef, npc, breeding, store)) {
                return MutationResult.alarmClearFailed(readyBefore);
            }
        } else {
            breeding.setReady(false);
        }
        breeding.setHappiness(resolveReadyHappiness(npcRef, store));
        breeding.setLastHappinessUpdateMs(System.currentTimeMillis());
        populateConfigId(npcRef, store, breeding);
        store.putComponent(npcRef, breedingType, breeding);
        return MutationResult.applied(readyBefore, breeding.isReady() && !breeding.isCooldownActive(now), breeding);
    }

    private static double resolveReadyHappiness(@Nonnull Ref<EntityStore> npcRef,
                                                @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType =
                TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType == null
                ? null : store.getComponent(npcRef, happinessType);
        return happiness == null ? READY_HAPPINESS : happiness.getValue();
    }

    private static void populateConfigId(@Nonnull Ref<EntityStore> npcRef,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull TameworkBreedingComponent breeding) {
        if (breeding.getConfigId() != null && !breeding.getConfigId().isBlank()) {
            return;
        }
        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        if (config != null && config.getId() != null && !config.getId().isBlank()) {
            breeding.setConfigId(config.getId());
        }
    }

    private static void sendResult(@Nonnull CommandContext context,
                                   @Nonnull NPCEntity npc,
                                   @Nonnull MutationResult result) {
        if (result.status == MutationStatus.SKIPPED) {
            context.sendMessage(Message.raw("NPC " + npc.getUuid() + " has no enabled breeding state."));
            return;
        }
        if (result.status == MutationStatus.ALARM_CLEAR_FAILED) {
            context.sendMessage(Message.raw("Could not clear the breeding cooldown alarm for NPC " + npc.getUuid() + "."));
            return;
        }
        context.sendMessage(Message.raw(
                "Set breeding readiness for NPC " + npc.getUuid() + ": readyNow "
                        + result.readyBefore + " -> " + result.readyAfter + "."
        ));
    }

    private static ReadyMode parseMode(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "1", "yes" -> ReadyMode.TRUE;
            case "false", "off", "0", "no" -> ReadyMode.FALSE;
            case "toggle" -> ReadyMode.TOGGLE;
            default -> null;
        };
    }

    private enum ReadyMode { TRUE, FALSE, TOGGLE }

    private enum MutationStatus { APPLIED, SKIPPED, ALARM_CLEAR_FAILED }

    private static final class MutationResult {
        private final MutationStatus status;
        private final boolean readyBefore;
        private final boolean readyAfter;

        private MutationResult(MutationStatus status, boolean readyBefore, boolean readyAfter) {
            this.status = status;
            this.readyBefore = readyBefore;
            this.readyAfter = readyAfter;
        }

        private static MutationResult applied(boolean readyBefore, boolean readyAfter,
                                              TameworkBreedingComponent breeding) {
            return new MutationResult(MutationStatus.APPLIED, readyBefore, readyAfter);
        }

        private static MutationResult skipped() {
            return new MutationResult(MutationStatus.SKIPPED, false, false);
        }

        private static MutationResult alarmClearFailed(boolean readyBefore) {
            return new MutationResult(MutationStatus.ALARM_CLEAR_FAILED, readyBefore, readyBefore);
        }
    }
}
