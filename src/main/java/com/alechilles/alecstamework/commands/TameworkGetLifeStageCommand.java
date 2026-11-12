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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * Command to inspect the targeted NPC life-stage state.
 */
public final class TameworkGetLifeStageCommand extends AbstractTameworkNpcTargetCommand {
    public TameworkGetLifeStageCommand() {
        super("lifestage", "server.tamework.commands.getLifeStage.description",
                "server.tamework.commands.getLifeStage.no.npc.found.in.view");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent component = type != null ? store.getComponent(npcRef, type) : null;
        String stage = CompanionLifeStageService.resolveCurrentStage(npcRef, store, roleId);
        if (component == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getLifeStage.life.stage.for.npc.fallback.role.gate").param("0", String.valueOf(npc.getUuid())).param("1", String.valueOf(stage)));
            return;
        }
        AnimalProgressionService.Presentation presentation =
                AnimalProgressionService.presentation(component, roleId, false);
        if (presentation != null) {
            stage = presentation.stage();
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getLifeStage.life.stage.for.npc.bornatms.adolescentatms.adultatms").param("0", String.valueOf(npc.getUuid())).param("1", String.valueOf(stage)).param("2", String.valueOf(component.getBornAtMs())).param("3", String.valueOf(component.getAdolescentAtMs())).param("4", String.valueOf(component.getAdultAtMs())).param("5", String.valueOf(component.isGrowthScalingEnabled())).param("6", String.valueOf(component.getAgeProgressMs())));
    }
}
