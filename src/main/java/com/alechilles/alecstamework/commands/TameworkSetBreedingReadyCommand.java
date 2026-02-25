package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Debug command to force/set breeding readiness on the targeted NPC.
 */
public final class TameworkSetBreedingReadyCommand extends AbstractPlayerCommand {

    public TameworkSetBreedingReadyCommand() {
        super("setbreedingready", "Set breeding ready state for the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        ReadyMode mode = parseMode(getFirstArg(commandContext));
        if (mode == ReadyMode.INVALID) {
            commandContext.sender().sendMessage(Message.raw(
                    "Usage: /tw setbreedingready [true|false|toggle]"
            ));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            commandContext.sender().sendMessage(Message.raw("Breeding component is not available."));
            return;
        }

        CompanionProgressionBootstrapService.ensureProgressionComponents(candidate.ref, store);
        TameworkBreedingComponent breeding = store.getComponent(candidate.ref, breedingType);
        if (breeding == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "NPC " + candidate.npcUuid + " has no breeding state."
            ));
            return;
        }

        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        boolean cooldownActiveBefore = breeding.isCooldownActive(now);
        boolean readyNowBefore = breeding.isReady() && !cooldownActiveBefore;
        boolean nextReady = switch (mode) {
            case TRUE -> true;
            case FALSE -> false;
            case TOGGLE -> !readyNowBefore;
            default -> false;
        };

        if (nextReady) {
            breeding.setReady(true);
            breeding.setCooldownUntilMs(0L);
            breeding.setLastPartnerUuid(null);
        } else {
            breeding.setReady(false);
        }
        breeding.setLastHappinessUpdateMs(now);
        maybeUpdateConfigId(candidate.ref, store, breeding);
        store.putComponent(candidate.ref, breedingType, breeding);

        boolean cooldownActiveAfter = breeding.isCooldownActive(now);
        boolean readyNowAfter = breeding.isReady() && !cooldownActiveAfter;
        commandContext.sender().sendMessage(Message.raw(
                "Set breeding readiness for NPC "
                        + candidate.npcUuid
                        + ": readyNow "
                        + readyNowBefore
                        + " -> "
                        + readyNowAfter
                        + ", readyFlag="
                        + breeding.isReady()
                        + ", cooldownUntilMs="
                        + breeding.getCooldownUntilMs()
                        + "."
        ));
    }

    private static void maybeUpdateConfigId(Ref<EntityStore> npcRef,
                                            Store<EntityStore> store,
                                            TameworkBreedingComponent breeding) {
        if (breeding == null || (breeding.getConfigId() != null && !breeding.getConfigId().isBlank())) {
            return;
        }
        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        if (config == null || config.getId() == null || config.getId().isBlank()) {
            return;
        }
        breeding.setConfigId(config.getId());
    }

    @Nullable
    private static String getFirstArg(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null || input.isBlank()) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        return tokens[2];
    }

    private static ReadyMode parseMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return ReadyMode.TRUE;
        }
        String value = raw.trim().toLowerCase();
        if ("toggle".equals(value)) {
            return ReadyMode.TOGGLE;
        }
        if ("true".equals(value) || "1".equals(value) || "on".equals(value) || "yes".equals(value)) {
            return ReadyMode.TRUE;
        }
        if ("false".equals(value) || "0".equals(value) || "off".equals(value) || "no".equals(value)) {
            return ReadyMode.FALSE;
        }
        return ReadyMode.INVALID;
    }

    private enum ReadyMode {
        TRUE,
        FALSE,
        TOGGLE,
        INVALID
    }
}
