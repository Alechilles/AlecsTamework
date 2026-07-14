package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.actions.BreedingCooldownResetService;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Debug command to force/set breeding readiness on the targeted NPC.
 */
public final class TameworkSetBreedingReadyCommand extends AbstractPlayerCommand {
    private static final String USAGE =
            "Usage: /tw setbreedingready [true|false|toggle] [aoe [radius]]";
    private final BreedingCooldownResetService cooldownResetService =
            new BreedingCooldownResetService();

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
        TameworkSetBreedingReadyCommandSupport.Arguments arguments =
                TameworkSetBreedingReadyCommandSupport.parse(commandContext.getInputString());
        if (!arguments.valid()) {
            commandContext.sender().sendMessage(Message.raw(USAGE));
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

        if (arguments.aoe()) {
            applyAoe(commandContext, store, candidate, breedingType, arguments);
            return;
        }
        MutationResult result = applyMutation(
                candidate, store, breedingType, arguments.mode(), false
        );
        sendSingleResult(commandContext, candidate, result);
    }

    private void applyAoe(@Nonnull CommandContext commandContext,
                          @Nonnull Store<EntityStore> store,
                          @Nonnull TameworkCommandTargeting.Candidate center,
                          @Nonnull ComponentType<EntityStore, TameworkBreedingComponent> breedingType,
                          @Nonnull TameworkSetBreedingReadyCommandSupport.Arguments arguments) {
        double radius = arguments.radius() != null
                ? arguments.radius()
                : resolveDefaultAoeRadius(center.ref, store, breedingType);
        List<TameworkCommandTargeting.Candidate> candidates =
                TameworkCommandTargeting.findNearbyNpcs(store, center.ref, radius);
        int updated = 0;
        int skipped = 0;
        int alarmFailures = 0;
        for (TameworkCommandTargeting.Candidate candidate : candidates) {
            MutationResult result = applyMutation(
                    candidate, store, breedingType, arguments.mode(), true
            );
            if (result.status() == MutationStatus.APPLIED) {
                updated++;
            } else if (result.status() == MutationStatus.ALARM_CLEAR_FAILED) {
                alarmFailures++;
            } else {
                skipped++;
            }
        }
        commandContext.sender().sendMessage(Message.raw(
                "Set breeding readiness in AOE around NPC " + center.npcUuid
                        + ": mode=" + arguments.mode().name().toLowerCase(Locale.ROOT)
                        + ", radius=" + formatRadius(radius)
                        + ", updated=" + updated
                        + ", skipped=" + skipped
                        + ", alarmClearFailures=" + alarmFailures + "."
        ));
    }

    @Nonnull
    private MutationResult applyMutation(
            @Nonnull TameworkCommandTargeting.Candidate candidate,
            @Nonnull Store<EntityStore> store,
            @Nonnull ComponentType<EntityStore, TameworkBreedingComponent> breedingType,
            @Nonnull TameworkSetBreedingReadyCommandSupport.ReadyMode mode,
            boolean requireEnabled) {
        if (candidate.ref == null || !candidate.ref.isValid()) {
            return MutationResult.skipped();
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(candidate.ref, store);
        TameworkBreedingComponent breeding = store.getComponent(candidate.ref, breedingType);
        if (breeding == null || (requireEnabled && !breeding.isEnabled())) {
            return MutationResult.skipped();
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
            NPCEntity npc = store.getComponent(candidate.ref, NPCEntity.getComponentType());
            if (!cooldownResetService.forceReady(candidate.ref, npc, breeding, store)) {
                return MutationResult.alarmClearFailed(readyNowBefore);
            }
        } else {
            breeding.setReady(false);
        }
        breeding.setLastHappinessUpdateMs(System.currentTimeMillis());
        maybeUpdateConfigId(candidate.ref, store, breeding);
        store.putComponent(candidate.ref, breedingType, breeding);

        boolean cooldownActiveAfter = breeding.isCooldownActive(now);
        boolean readyNowAfter = breeding.isReady() && !cooldownActiveAfter;
        return MutationResult.applied(readyNowBefore, readyNowAfter, breeding);
    }

    private static void sendSingleResult(
            @Nonnull CommandContext commandContext,
            @Nonnull TameworkCommandTargeting.Candidate candidate,
            @Nonnull MutationResult result) {
        if (result.status() == MutationStatus.SKIPPED) {
            commandContext.sender().sendMessage(Message.raw(
                    "NPC " + candidate.npcUuid + " has no enabled breeding state."
            ));
            return;
        }
        if (result.status() == MutationStatus.ALARM_CLEAR_FAILED) {
            commandContext.sender().sendMessage(Message.raw(
                    "Breeding readiness was not changed for NPC " + candidate.npcUuid
                            + " because its cooldown alarm could not be cleared."
            ));
            return;
        }
        TameworkBreedingComponent breeding = result.breeding();
        commandContext.sender().sendMessage(Message.raw(
                "Set breeding readiness for NPC "
                        + candidate.npcUuid
                        + ": readyNow "
                        + result.readyBefore()
                        + " -> "
                        + result.readyAfter()
                        + ", readyFlag="
                        + breeding.isReady()
                        + ", cooldownUntilMs="
                        + breeding.getCooldownUntilMs()
                        + "."
        ));
    }

    private static double resolveDefaultAoeRadius(
            @Nonnull Ref<EntityStore> centerRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull ComponentType<EntityStore, TameworkBreedingComponent> breedingType) {
        CompanionProgressionBootstrapService.ensureProgressionComponents(centerRef, store);
        TameworkBreedingComponent breeding = store.getComponent(centerRef, breedingType);
        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(centerRef, store, breeding);
        String roleId = CompanionRoleIdResolver.resolveRoleId(centerRef, store);
        TwBreedingConfig.PairingSettings pairing = config != null ? config.resolvePairing(roleId) : null;
        double radius = pairing != null
                ? pairing.getBreedRadius()
                : TameworkSetBreedingReadyCommandSupport.DEFAULT_AOE_RADIUS;
        return Double.isFinite(radius) && radius > 0.0
                ? radius
                : TameworkSetBreedingReadyCommandSupport.DEFAULT_AOE_RADIUS;
    }

    private static String formatRadius(double radius) {
        return radius == Math.rint(radius)
                ? Long.toString(Math.round(radius))
                : Double.toString(radius);
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

    private enum MutationStatus {
        APPLIED,
        SKIPPED,
        ALARM_CLEAR_FAILED
    }

    private record MutationResult(MutationStatus status,
                                  boolean readyBefore,
                                  boolean readyAfter,
                                  @Nullable TameworkBreedingComponent breeding) {
        private static MutationResult applied(boolean readyBefore,
                                              boolean readyAfter,
                                              TameworkBreedingComponent breeding) {
            return new MutationResult(MutationStatus.APPLIED, readyBefore, readyAfter, breeding);
        }

        private static MutationResult skipped() {
            return new MutationResult(MutationStatus.SKIPPED, false, false, null);
        }

        private static MutationResult alarmClearFailed(boolean readyBefore) {
            return new MutationResult(
                    MutationStatus.ALARM_CLEAR_FAILED, readyBefore, readyBefore, null
            );
        }
    }
}
