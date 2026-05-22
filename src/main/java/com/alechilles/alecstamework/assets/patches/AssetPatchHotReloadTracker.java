package com.alechilles.alecstamework.assets.patches;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;

/**
 * Tracks asynchronous Hytale file-watcher reloads for generated patch-pack assets.
 */
public final class AssetPatchHotReloadTracker {
    private final Object lock = new Object();
    private final String generatedPackId;
    private long sequence;
    private final Set<Observation> observations = new LinkedHashSet<>();

    public AssetPatchHotReloadTracker(@Nonnull String generatedPackId) {
        this.generatedPackId = generatedPackId;
    }

    public long mark() {
        synchronized (lock) {
            return sequence;
        }
    }

    public void recordLoadedAssets(@Nonnull Class<?> assetClass,
                                   @Nullable AssetMap<?, ?> assetMap,
                                   @Nonnull Collection<?> keys) {
        if (assetMap == null || keys.isEmpty()) {
            return;
        }
        synchronized (lock) {
            boolean changed = false;
            for (Object key : keys) {
                if (key == null || !generatedPackOwns(assetMap, key)) {
                    continue;
                }
                observations.add(new Observation(++sequence, assetClass.getName(), String.valueOf(key)));
                changed = true;
            }
            if (changed) {
                lock.notifyAll();
            }
        }
    }

    @Nonnull
    public Set<String> awaitHotReloadedTargets(@Nonnull Collection<String> targets,
                                               long sinceSequence,
                                               @Nonnull Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        LinkedHashSet<String> remaining = new LinkedHashSet<>();
        for (String target : targets) {
            if (targetObservation(target) != null) {
                remaining.add(AssetPatchDefinition.normalizeAssetPath(target));
            }
        }
        if (remaining.isEmpty()) {
            return Set.of();
        }

        synchronized (lock) {
            LinkedHashSet<String> observed = observedTargets(remaining, sinceSequence);
            while (!observed.containsAll(remaining)) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    long millis = Math.max(1L, remainingNanos / 1_000_000L);
                    lock.wait(millis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                observed = observedTargets(remaining, sinceSequence);
            }
            return Set.copyOf(observed);
        }
    }

    @Nonnull
    private LinkedHashSet<String> observedTargets(@Nonnull Set<String> targets, long sinceSequence) {
        LinkedHashSet<String> observed = new LinkedHashSet<>();
        for (String target : targets) {
            TargetObservation expected = targetObservation(target);
            if (expected == null) {
                continue;
            }
            for (Observation observation : observations) {
                if (observation.sequence() > sinceSequence && observation.matches(expected)) {
                    observed.add(target);
                    break;
                }
            }
        }
        return observed;
    }

    @Nullable
    private static TargetObservation targetObservation(@Nonnull String target) {
        String normalized = AssetPatchDefinition.normalizeAssetPath(target);
        String key = assetKey(normalized);
        if (key == null) {
            return null;
        }
        if (normalized.startsWith("Server/Item/Items/") && normalized.endsWith(".json")) {
            return new TargetObservation(Item.class.getName(), key);
        }
        if (normalized.startsWith("Server/Particles/") && normalized.endsWith(".particlesystem")) {
            return new TargetObservation(ParticleSystem.class.getName(), key);
        }
        if (normalized.startsWith("Server/Tamework/Items/Commands/") && normalized.endsWith(".json")) {
            return new TargetObservation(TwCommandItemConfig.class.getName(), key);
        }
        return null;
    }

    @Nullable
    private static String assetKey(@Nonnull String target) {
        int slash = target.lastIndexOf('/');
        String fileName = slash >= 0 ? target.substring(slash + 1) : target;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".particlesystem")) {
            return fileName.substring(0, fileName.length() - ".particlesystem".length());
        }
        if (lower.endsWith(".json")) {
            return fileName.substring(0, fileName.length() - ".json".length());
        }
        return null;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean generatedPackOwns(@Nonnull AssetMap<?, ?> assetMap, @Nonnull Object key) {
        try {
            AssetMap rawMap = assetMap;
            Object pack = rawMap.getAssetPack(key);
            return generatedPackId.equals(String.valueOf(pack));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private record Observation(long sequence, @Nonnull String assetClassName, @Nonnull String key) {
        boolean matches(@Nonnull TargetObservation expected) {
            return assetClassName.equals(expected.assetClassName()) && key.equals(expected.key());
        }
    }

    private record TargetObservation(@Nonnull String assetClassName, @Nonnull String key) {
    }
}
