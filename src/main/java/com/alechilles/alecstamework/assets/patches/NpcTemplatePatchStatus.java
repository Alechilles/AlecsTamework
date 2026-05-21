package com.alechilles.alecstamework.assets.patches;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * Captures the last optional NPC template patch run for commands and logs.
 */
public final class NpcTemplatePatchStatus {
    private final List<String> applied = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private final List<String> generatedTargets = new ArrayList<>();

    public void addApplied(@Nonnull String message) {
        applied.add(message);
    }

    public void addSkipped(@Nonnull String message) {
        skipped.add(message);
    }

    public void addFailed(@Nonnull String message) {
        failed.add(message);
    }

    public void addGeneratedTarget(@Nonnull String target) {
        generatedTargets.add(target);
    }

    @Nonnull
    public List<String> getApplied() {
        return List.copyOf(applied);
    }

    @Nonnull
    public List<String> getSkipped() {
        return List.copyOf(skipped);
    }

    @Nonnull
    public List<String> getFailed() {
        return List.copyOf(failed);
    }

    @Nonnull
    public List<String> getGeneratedTargets() {
        return List.copyOf(generatedTargets);
    }

    public boolean hasFailures() {
        return !failed.isEmpty();
    }

    @Nonnull
    public String summaryLine() {
        return "Patches applied=" + applied.size()
                + " skipped=" + skipped.size()
                + " failed=" + failed.size()
                + " generatedTargets=" + generatedTargets.size();
    }
}
