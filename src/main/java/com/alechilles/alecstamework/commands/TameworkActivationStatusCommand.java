package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.runtime.activation.TameworkReloadTopologyReport;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationState;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeDiagnostics;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import javax.annotation.Nonnull;

/** Shows the frozen runtime topology and passive per-module counters. */
public final class TameworkActivationStatusCommand extends AbstractTameworkServerCommand {
    public TameworkActivationStatusCommand() {
        super("status", "server.tamework.commands.activationStatus.description");
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        Tamework plugin = Tamework.getInstance();
        TameworkRuntimeActivationState state = plugin == null
                ? null : plugin.getRuntimeActivationState();
        if (state == null) {
            send(context, Message.translation("server.tamework.commands.activationStatus.tamework.activation.state.is.not.available"));
            return;
        }
        TameworkReloadTopologyReport reload = plugin.compareRuntimeActivationTopology();
        send(context, Message.translation("server.tamework.commands.activationStatus.activation.fingerprint.reload").param("0", String.valueOf(state.topologyFingerprint())).param("1", String.valueOf((reload == null ? "unknown" : reload.summary()))));
        for (TameworkRuntimeModule module : state.plan().modules()) {
            TameworkRuntimeDiagnostics.ModuleSnapshot snapshot = state.diagnostics().module(module);
            TameworkRuntimeDiagnostics.CounterSnapshot counters = snapshot.counters();
            send(context, Message.translation("server.tamework.commands.activationStatus.systems.callbacks.workcycles.workers.subscriptions.databaseopens.reasons").param("0", String.valueOf(module.id())).param("1", String.valueOf(snapshot.state())).param("2", String.valueOf(counters.systemRegistrations())).param("3", String.valueOf(counters.callbacks())).param("4", String.valueOf(counters.workCycles())).param("5", String.valueOf(counters.workerStarts())).param("6", String.valueOf(counters.subscriptions())).param("7", String.valueOf(counters.databaseOpens())).param("8", String.valueOf(snapshot.reasons())));
        }
    }

    private static void send(CommandContext context, Message text) {
        context.sender().sendMessage(text);
    }
}
