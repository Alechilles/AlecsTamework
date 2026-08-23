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
        super("status", "Show Tamework runtime activation status.");
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        Tamework plugin = Tamework.getInstance();
        TameworkRuntimeActivationState state = plugin == null
                ? null : plugin.getRuntimeActivationState();
        if (state == null) {
            send(context, "Tamework activation state is not available.");
            return;
        }
        TameworkReloadTopologyReport reload = plugin.compareRuntimeActivationTopology();
        send(context, "Activation fingerprint=" + state.topologyFingerprint()
                + ", reload=" + (reload == null ? "unknown" : reload.summary()) + ".");
        for (TameworkRuntimeModule module : state.plan().modules()) {
            TameworkRuntimeDiagnostics.ModuleSnapshot snapshot = state.diagnostics().module(module);
            TameworkRuntimeDiagnostics.CounterSnapshot counters = snapshot.counters();
            send(context, module.id() + "=" + snapshot.state()
                    + ", systems=" + counters.systemRegistrations()
                    + ", callbacks=" + counters.callbacks()
                    + ", workCycles=" + counters.workCycles()
                    + ", workers=" + counters.workerStarts()
                    + ", subscriptions=" + counters.subscriptions()
                    + ", databaseOpens=" + counters.databaseOpens()
                    + ", reasons=" + snapshot.reasons() + ".");
        }
    }

    private static void send(CommandContext context, String text) {
        context.sender().sendMessage(Message.raw(text));
    }
}
