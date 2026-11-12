package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.access.OwnerGrants;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.items.CommandLinkedPanelCooldownSnapshotService;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Prints one machine-readable line with a companion's state, read from memory on its world thread
 * and changing nothing: role, tamed, owner, the players it is shared with, life stage, level and XP,
 * happiness, needs, harvest and breeding readiness, and traits. Keys the animal has no state for are
 * left out. Works from the console with {@code --entity <uuid>}.
 */
public final class TameworkGetCompanionCommand extends AbstractTameworkNpcTargetCommand {

    public TameworkGetCompanionCommand() {
        super("companion", "server.tamework.commands.getCompanion.description",
                "server.tamework.commands.getCompanion.no.npc.found.in.view");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        StringBuilder line = new StringBuilder("ok entity=").append(npc.getUuid())
                .append(" role=").append(roleId == null || roleId.isBlank() ? npc.getRoleName() : roleId)
                .append(" tamed=").append(TamedStateResolver.isTamed(npcRef, store));

        TameworkOwnerComponent owner = component(store, npcRef, TameworkOwnerComponent.getComponentType());
        UUID ownerId = owner == null ? null : owner.getOwnerId();
        line.append(" owner=").append(ownerId == null ? "none" : ownerId.toString());
        if (ownerId != null) {
            appendSharing(line, ownerId, component(store, npcRef, TameworkCompanionComponent.getComponentType()));
        }

        appendStage(line, store, npcRef, roleId);

        CompanionLevelingService.LevelingSnapshot leveling = CompanionLevelingService.resolveSnapshot(npcRef, store, roleId);
        if (leveling != null) {
            line.append(" level=").append(leveling.level())
                    .append(" xp=").append(format(leveling.currentXp()))
                    .append(" totalXp=").append(format(leveling.totalXp()));
        }

        TameworkGetHappinessCommand.HappinessSnapshot happiness =
                TameworkGetHappinessCommand.resolveHappinessSnapshot(npcRef, store);
        if (happiness != null) {
            line.append(" happiness=").append(format(happiness.value()));
        }

        TameworkNeedsComponent needs = component(store, npcRef, TameworkNeedsComponent.getComponentType());
        TwNeedsConfig needsConfig = needs == null ? null : NeedsConfigResolver.resolveConfig(npcRef, store, needs);
        if (needs != null && NeedsConfigResolver.isRuntimeEnabled(needsConfig)) {
            line.append(" hunger=").append(format(needs.getHunger()))
                    .append(" thirst=").append(format(needs.getThirst()));
        }

        TameworkAlarmService.Snapshot harvest = TameworkAlarmService.snapshot(
                npcRef, store, CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName());
        if (harvest.valid) {
            line.append(" harvestReady=").append(harvest.ready)
                    .append(" harvestRemainingMs=")
                    .append(harvest.active ? Math.max(0L, harvest.untilMs - harvest.nowMs) : 0L);
        }

        TameworkBreedingComponent breeding = component(store, npcRef, TameworkBreedingComponent.getComponentType());
        if (breeding != null && breeding.isEnabled()) {
            // Breeding deadlines are judged on the animal's own progression time, as harvest is.
            long now = AnimalProgressionService.currentTimeMs(npcRef, store);
            line.append(" breedingReady=").append(breeding.isReady() && !breeding.isCooldownActive(now))
                    .append(" breedingCooldownRemainingMs=")
                    .append(BreedingTimeService.remainingDurationMs(breeding.getCooldownUntilMs(), now));
        }

        TameworkTraitsComponent traits = component(store, npcRef, TameworkTraitsComponent.getComponentType());
        if (traits != null) {
            line.append(" traits=").append(TameworkTraitCommandSupport.formatTraitPairs(traits));
        }
        context.sendMessage(Message.raw(line.toString()));
    }

    /**
     * Who the owner shares this companion with: named players ({@code sharers}), groups turned on
     * ({@code shareGroups}, only when any) and {@code private=true} when the owner keeps it private.
     */
    private static void appendSharing(@Nonnull StringBuilder line, @Nonnull UUID ownerId,
                                      @Nullable TameworkCompanionComponent companion) {
        SharedAccessService access = SharedAccessService.get();
        UUID profileId = companion == null ? null : companion.getProfileId();
        if (profileId != null && access.isPrivate(ownerId, profileId)) {
            line.append(" sharers=none private=true");
            return;
        }
        OwnerGrants grants = access.grants(ownerId);
        line.append(" sharers=").append(grants.players().isEmpty() ? "none" : String.join(",",
                grants.players().keySet().stream().map(UUID::toString).sorted().toList()));
        if (!grants.groups().isEmpty()) {
            line.append(" shareGroups=").append(String.join(",", grants.groups().keySet().stream().sorted().toList()));
        }
    }

    private static void appendStage(@Nonnull StringBuilder line, @Nonnull Store<EntityStore> store,
                                    @Nonnull Ref<EntityStore> npcRef, @Nullable String roleId) {
        String stage = CompanionLifeStageService.resolveCurrentStage(npcRef, store, roleId);
        TameworkLifeStageComponent lifeStage = component(store, npcRef, TameworkLifeStageComponent.getComponentType());
        AnimalProgressionService.Presentation presentation = lifeStage == null
                ? null
                : AnimalProgressionService.presentation(lifeStage, roleId, false);
        if (presentation != null && presentation.stage() != null) {
            stage = presentation.stage();
        }
        if (stage != null && !stage.isBlank()) {
            line.append(" stage=").append(stage);
        }
    }

    @Nullable
    private static <T extends Component<EntityStore>> T component(@Nonnull Store<EntityStore> store,
                                                                  @Nonnull Ref<EntityStore> ref,
                                                                  @Nullable ComponentType<EntityStore, T> type) {
        return type == null ? null : store.getComponent(ref, type);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", Double.isFinite(value) ? value : 0.0);
    }
}
