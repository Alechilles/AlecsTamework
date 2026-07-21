package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;

/** Concise integration/readiness report for public API consumers. */
public final class TameworkDiagnoseCommand extends AbstractPlayerCommand {
    public TameworkDiagnoseCommand() {
        super("diagnose", "Report Tamework API and integration runtime readiness.");
        setAllowsExtraArguments(true);
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
        TameworkPersistenceRuntime persistence = plugin.getPersistenceRuntime();
        if (persistence == null) {
            context.sender().sendMessage(Message.raw("SQLite persistence runtime is not available."));
            return;
        }
        TameworkIntegrationDiagnosticsService diagnostics =
                TameworkIntegrationDiagnosticsService.live(
                        plugin.getApi(), persistence, plugin.isCaptureAttemptRuntimeReady(),
                        plugin.getApiEventBus());
        List<String> arguments = arguments(context);
        List<String> lines;
        if (arguments.isEmpty()) {
            lines = diagnostics.overview();
        } else {
            lines = switch (arguments.getFirst().toLowerCase(Locale.ROOT)) {
                case "population" -> arguments.size() == 1
                        ? diagnostics.population() : usage();
                case "capture-attempt" -> arguments.size() == 2
                        ? diagnostics.captureAttempt(arguments.get(1)) : usage();
                case "vessel" -> arguments.size() == 2
                        ? diagnostics.vessel(arguments.get(1)) : usage();
                case "provisioning" -> arguments.size() == 3
                        ? diagnostics.provisioning(arguments.get(1), arguments.get(2)) : usage();
                default -> usage();
            };
        }
        for (String line : lines) {
            context.sender().sendMessage(Message.raw(line));
        }
    }

    private static List<String> usage() {
        return List.of("Usage: /tw diagnose [population|capture-attempt <id>"
                + "|vessel <binding-or-profile>"
                + "|provisioning <caller-namespace> <idempotency-key>]");
    }

    private static List<String> arguments(CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) return List.of();
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= 2) return List.of();
        return List.copyOf(Arrays.asList(tokens).subList(2, tokens.length));
    }
}
