package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
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
import javax.annotation.Nonnull;

/**
 * Command to inspect the targeted NPC life-stage state.
 */
public final class TameworkGetLifeStageCommand extends AbstractPlayerCommand {
    public TameworkGetLifeStageCommand() {
        super("lifestage", "server.tamework.commands.getLifeStage.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getLifeStage.no.npc.found.in.view"));
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(candidate.ref, store);
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent component = type != null ? store.getComponent(candidate.ref, type) : null;
        String stage = CompanionLifeStageService.resolveCurrentStage(candidate.ref, store, roleId);
        if (component == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getLifeStage.life.stage.for.npc.fallback.role.gate").param("0", String.valueOf(candidate.npcUuid)).param("1", String.valueOf(stage)));
            return;
        }
        AnimalProgressionService.Presentation presentation =
                AnimalProgressionService.presentation(component, roleId, false);
        if (presentation != null) {
            stage = presentation.stage();
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getLifeStage.life.stage.for.npc.bornatms.adolescentatms.adultatms").param("0", String.valueOf(candidate.npcUuid)).param("1", String.valueOf(stage)).param("2", String.valueOf(component.getBornAtMs())).param("3", String.valueOf(component.getAdolescentAtMs())).param("4", String.valueOf(component.getAdultAtMs())).param("5", String.valueOf(component.isGrowthScalingEnabled())).param("6", String.valueOf(component.getAgeProgressMs())));
    }
}
