package com.alechilles.alecstamework.commands;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;

import com.alechilles.alecstamework.npc.actions.HarvestReadyDebugService;
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
import javax.annotation.Nullable;

/** Sets harvest readiness for NPCs selected with the standard NPC debug selectors. */
public final class TameworkSetHarvestReadyCommand extends NPCMultiSelectCommandBase {
    private final OptionalArg<String> modeArg = withOptionalArg(
            "mode", "server.tamework.commands.setHarvestReady.argument.mode", STRING
    ).suggest((sender, entered, parameters, suggestions) -> {
        suggestions.suggest("true");
        suggestions.suggest("false");
        suggestions.suggest("toggle");
    });
    private final HarvestReadyDebugService service = new HarvestReadyDebugService();

    public TameworkSetHarvestReadyCommand() {
        super("harvestready", "server.tamework.commands.setHarvestReady.description");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        boolean readyBefore = service.isReady(npcRef, store);
        String rawMode = modeArg.provided(context) ? modeArg.get(context) : null;
        Boolean requestedReady = resolveRequestedReadiness(rawMode, readyBefore);
        if (requestedReady == null) {
            context.sendMessage(Message.translation(
                    "server.tamework.commands.setHarvestReady.mode.must.be.true.false.or.toggle"));
            return;
        }

        HarvestReadyDebugService.Result result = service.setReady(
                npcRef, npc.getRole(), store, requestedReady
        );
        switch (result.status()) {
            case APPLIED -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setHarvestReady.result")
                    .param("0", String.valueOf(npc.getUuid()))
                    .param("1", String.valueOf(result.before().ready))
                    .param("2", String.valueOf(result.after().ready))
                    .param("3", result.after().name));
            case ALARM_UNAVAILABLE -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setHarvestReady.alarm.unavailable")
                    .param("0", String.valueOf(npc.getUuid())));
            case TIMEOUT_UNAVAILABLE -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setHarvestReady.timeout.unavailable")
                    .param("0", String.valueOf(npc.getUuid())));
            case MUTATION_FAILED -> context.sendMessage(Message.translation(
                            "server.tamework.commands.setHarvestReady.mutation.failed")
                    .param("0", String.valueOf(npc.getUuid()))
                    .param("1", result.before().name));
        }
    }

    @Nullable
    static Boolean resolveRequestedReadiness(@Nullable String raw, boolean readyBefore) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "1", "yes" -> true;
            case "false", "off", "0", "no" -> false;
            case "toggle" -> !readyBefore;
            default -> null;
        };
    }
}
