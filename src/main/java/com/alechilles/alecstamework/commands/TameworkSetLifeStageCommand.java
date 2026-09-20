package com.alechilles.alecstamework.commands;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;

import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.commands.NPCMultiSelectCommandBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/** Sets the life stage of NPCs selected with the standard NPC debug selectors. */
public final class TameworkSetLifeStageCommand extends NPCMultiSelectCommandBase {
    private final RequiredArg<String> stageArg = withRequiredArg(
            "stage", "server.tamework.commands.setLifeStage.argument.stage", STRING
    ).suggest((sender, entered, parameters, suggestions) -> {
        suggestions.suggest("baby");
        suggestions.suggest("adolescent");
        suggestions.suggest("adult");
        suggestions.suggest("prime");
        suggestions.suggest("senior");
    });

    public TameworkSetLifeStageCommand() {
        super("lifestage", "server.tamework.commands.setLifeStage.description");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        String requestedStage = stageArg.get(context);
        CompanionLifeStageService.DebugSetResult result =
                CompanionLifeStageService.setLifeStageForDebug(
                        npcRef, npc, store, requestedStage
                );
        switch (result.status()) {
            case APPLIED -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setLifeStage.result")
                    .param("0", String.valueOf(npc.getUuid()))
                    .param("1", String.valueOf(result.previousStage()))
                    .param("2", String.valueOf(result.currentStage())));
            case INVALID_STAGE -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setLifeStage.invalid.stage")
                    .param("0", String.valueOf(requestedStage)));
            case COMPONENT_UNAVAILABLE -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setLifeStage.component.unavailable")
                    .param("0", String.valueOf(npc.getUuid())));
            case JUVENILE_LIFECYCLE_UNAVAILABLE -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setLifeStage.juvenile.unavailable")
                    .param("0", String.valueOf(npc.getUuid())));
            case ADULT_AGING_UNAVAILABLE -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setLifeStage.aging.unavailable")
                    .param("0", String.valueOf(npc.getUuid())));
            case STAGE_UNAVAILABLE -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setLifeStage.stage.unavailable")
                    .param("0", String.valueOf(npc.getUuid()))
                    .param("1", String.valueOf(requestedStage)));
        }
    }
}
