package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
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
        super("getlifestage", "Get life stage of the NPC you are looking at.");
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
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(candidate.ref, store);
        String stage = CompanionLifeStageService.resolveCurrentStage(candidate.ref, store, roleId);
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent component = type != null ? store.getComponent(candidate.ref, type) : null;
        if (component == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "Life stage for NPC " + candidate.npcUuid + ": " + stage + " (fallback role gate, no component)."
            ));
            return;
        }
        commandContext.sender().sendMessage(Message.raw(
                "Life stage for NPC " + candidate.npcUuid
                        + ": " + stage
                        + " (bornAtMs=" + component.getBornAtMs()
                        + ", adolescentAtMs=" + component.getAdolescentAtMs()
                        + ", adultAtMs=" + component.getAdultAtMs()
                        + ", growthScaling=" + component.isGrowthScalingEnabled()
                        + ")."
        ));
    }
}
