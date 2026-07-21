package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Concise integration/readiness report for public API consumers. */
public final class TameworkDiagnoseCommand extends AbstractPlayerCommand {
    public TameworkDiagnoseCommand() {
        super("diagnose", "Report Tamework API and integration runtime readiness.");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            context.sender().sendMessage(Message.raw("Tamework plugin not available."));
            return;
        }
        TameworkApi api = plugin.getApi();
        TameworkPersistenceRuntime persistence = plugin.getPersistenceRuntime();
        context.sender().sendMessage(Message.raw(
                "Tamework API=" + (api == null ? "unavailable" : api.getApiVersion())
                        + " capabilities=" + (api == null ? "[]" : api.getCapabilities())));
        context.sender().sendMessage(Message.raw(
                "Integration readiness: capturePolicy=" + plugin.isCaptureAttemptRuntimeReady()
                        + ", bondedVessels=" + has(api, "BONDED_VESSELS")
                        + ", populationGroups=" + has(api, "POPULATION_GROUPS")
                        + ", provisioning=" + has(api, "COMPANION_PROVISIONING")));
        context.sender().sendMessage(Message.raw(
                "Persistence=" + (persistence == null
                        ? "unavailable"
                        : persistence.getHealthState().status() + " reason="
                        + persistence.getHealthState().reason())));
    }

    private static boolean has(TameworkApi api, String capability) {
        return api != null && api.getCapabilities().stream().anyMatch(value -> value.name().equals(capability));
    }
}
