package com.alechilles.alecstamework.config.bonded;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Compiles bonded rosters and their dependent command configs before publishing
 * either registry generation.
 */
public final class BondedCompanionConfigReloadService {
    private final BondedCompanionRosterRegistry rosters;
    private final CommandItemRegistry commands;

    public BondedCompanionConfigReloadService(
            @Nonnull BondedCompanionRosterRegistry rosters,
            @Nonnull CommandItemRegistry commands
    ) {
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Publishes one coherent candidate or retains both active generations.
     */
    @Nonnull
    public ReloadResult reload(
            @Nonnull Collection<TwBondedCompanionRosterConfig> rosterConfigs,
            @Nonnull Collection<TwCommandItemConfig> commandConfigs
    ) {
        Objects.requireNonNull(rosterConfigs, "rosterConfigs");
        Objects.requireNonNull(commandConfigs, "commandConfigs");
        synchronized (rosters) {
            synchronized (commands) {
                return reloadLocked(rosterConfigs, commandConfigs);
            }
        }
    }

    private ReloadResult reloadLocked(
            Collection<TwBondedCompanionRosterConfig> rosterConfigs,
            Collection<TwCommandItemConfig> commandConfigs
    ) {
        long activeRosterRevision = rosters.snapshot().revision();
        long activeCommandRevision = commands.revision();
        try {
            BondedCompanionRosterRegistry.PreparedReplacement rosterCandidate =
                    rosters.prepareReplacement(
                            rosterConfigs,
                            Math.addExact(activeRosterRevision, 1L)
                    );
            CommandItemRegistry.PreparedReplacement commandCandidate =
                    commands.prepareReplacement(
                            commandConfigs,
                            rosterCandidate.candidate()
                    );
            if (!rosters.publishPrepared(rosterCandidate)
                    || !commands.publishPrepared(commandCandidate)) {
                throw new IllegalStateException(
                        "bonded-config-generation-publication-raced"
                );
            }
            return new ReloadResult(
                    true,
                    rosterCandidate.candidate().byRosterId().size(),
                    commandCandidate.loadedCount(),
                    rosters.snapshot().revision(),
                    commands.revision(),
                    List.of()
            );
        } catch (RuntimeException | LinkageError invalid) {
            return new ReloadResult(
                    false,
                    rosters.snapshot().byRosterId().size(),
                    commands.snapshot().size(),
                    rosters.snapshot().revision(),
                    commands.revision(),
                    List.of(safeReason(invalid))
            );
        }
    }

    private static String safeReason(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Immutable outcome for one roster/command generation attempt. */
    public record ReloadResult(
            boolean applied,
            int rosterCount,
            int commandCount,
            long rosterRevision,
            long commandRevision,
            @Nonnull List<String> errors
    ) {
        public ReloadResult {
            if (rosterCount < 0 || commandCount < 0
                    || rosterRevision < 0L || commandRevision < 0L) {
                throw new IllegalArgumentException(
                        "Reload counts and revisions cannot be negative."
                );
            }
            errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
            if (applied == !errors.isEmpty()) {
                throw new IllegalArgumentException(
                        "Applied reloads cannot contain errors."
                );
            }
        }
    }
}
