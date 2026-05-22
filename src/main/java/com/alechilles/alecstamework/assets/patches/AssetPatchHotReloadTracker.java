package com.alechilles.alecstamework.assets.patches;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;

/**
 * Tracks asynchronous Hytale file-watcher reloads for generated patch-pack assets.
 */
public final class AssetPatchHotReloadTracker {
    private final Object lock = new Object();
    private final String generatedPackId;
    private long sequence;
    private final Set<Observation> pendingGeneratedLoads = new LinkedHashSet<>();
    private final Set<Observation> observations = new LinkedHashSet<>();
    private final Map<String, PendingCommonObservation> pendingGeneratedCommonLoads = new LinkedHashMap<>();

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
                if (key == null || !isConfirmedGeneratedLoad(assetClass, assetMap, key)) {
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

    public void recordGeneratedAssetStoreMonitor(@Nonnull Class<?> assetClass,
                                                 @Nullable String assetPack,
                                                 @Nonnull Collection<Path> paths) {
        if (!generatedPackId.equals(assetPack) || paths.isEmpty()) {
            return;
        }
        synchronized (lock) {
            boolean changed = false;
            for (Path path : paths) {
                TargetObservation expected = targetObservation(path);
                if (expected == null || !expected.assetClassName().equals(assetClass.getName())) {
                    continue;
                }
                pendingGeneratedLoads.add(new Observation(++sequence, expected.assetClassName(), expected.key()));
                changed = true;
            }
            if (changed) {
                lock.notifyAll();
            }
        }
    }

    public void recordGeneratedCommonAssetMonitor(@Nullable String assetPack,
                                                  @Nonnull Collection<Path> paths) {
        if (!generatedPackId.equals(assetPack) || paths.isEmpty()) {
            return;
        }
        ArrayList<PendingCommonObservation> pending = new ArrayList<>();
        for (Path path : paths) {
            PendingCommonObservation observation = pendingCommonObservation(path);
            if (observation != null) {
                pending.add(observation);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (PendingCommonObservation observation : pending) {
                pendingGeneratedCommonLoads.put(
                        observation.target(),
                        observation.withSequence(++sequence)
                );
            }
            lock.notifyAll();
        }
    }

    @Nonnull
    public Set<String> awaitHotReloadedTargets(@Nonnull Collection<String> targets,
                                               long sinceSequence,
                                               @Nonnull Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        LinkedHashSet<String> remaining = new LinkedHashSet<>();
        for (String target : targets) {
            String normalized = AssetPatchDefinition.normalizeAssetPath(target);
            if (targetObservation(normalized) != null || isCommonTarget(normalized)) {
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
                    long millis = Math.min(100L, Math.max(1L, remainingNanos / 1_000_000L));
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
            if (expected != null) {
                for (Observation observation : observations) {
                    if (observation.sequence() > sinceSequence && observation.matches(expected)) {
                        observed.add(target);
                        break;
                    }
                }
            }
            if (observed.contains(target)) {
                continue;
            }
            PendingCommonObservation commonObservation = pendingGeneratedCommonLoads.get(target);
            if (commonObservation != null
                    && commonObservation.sequence() > sinceSequence
                    && isGeneratedCommonActive(commonObservation)) {
                observed.add(target);
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
    private static PendingCommonObservation pendingCommonObservation(@Nonnull Path path) {
        String normalizedPath = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        String marker = "/GeneratedPatches/";
        int generatedRoot = normalizedPath.lastIndexOf(marker);
        if (generatedRoot < 0) {
            marker = "GeneratedPatches/";
            generatedRoot = normalizedPath.indexOf(marker);
            if (generatedRoot < 0) {
                return null;
            }
        }
        String target = AssetPatchDefinition.normalizeAssetPath(normalizedPath.substring(generatedRoot + marker.length()));
        if (!isCommonTarget(target)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new PendingCommonObservation(
                    0L,
                    target,
                    target.substring("Common/".length()),
                    path.toAbsolutePath().normalize(),
                    CommonAsset.hash(bytes)
            );
        } catch (IOException ex) {
            return null;
        }
    }

    private static boolean isCommonTarget(@Nonnull String target) {
        return AssetPatchDefinition.normalizeAssetPath(target).startsWith("Common/");
    }

    @Nullable
    private static TargetObservation targetObservation(@Nonnull Path path) {
        String normalizedPath = path.toString().replace('\\', '/');
        String marker = "/GeneratedPatches/";
        int generatedRoot = normalizedPath.lastIndexOf(marker);
        if (generatedRoot < 0) {
            marker = "GeneratedPatches/";
            generatedRoot = normalizedPath.indexOf(marker);
            if (generatedRoot < 0) {
                return null;
            }
        }
        String target = normalizedPath.substring(generatedRoot + marker.length());
        return targetObservation(target);
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
    private boolean isConfirmedGeneratedLoad(@Nonnull Class<?> assetClass,
                                             @Nonnull AssetMap<?, ?> assetMap,
                                             @Nonnull Object key) {
        try {
            AssetMap rawMap = assetMap;
            Object pack = rawMap.getAssetPack(key);
            if (!generatedPackId.equals(String.valueOf(pack))) {
                return false;
            }
            Object path = rawMap.getPath(key);
            if (path instanceof Path assetPath && targetObservation(assetPath) == null) {
                return false;
            }
            TargetObservation expected = new TargetObservation(assetClass.getName(), String.valueOf(key));
            return pendingGeneratedLoads.stream().anyMatch(observation -> observation.matches(expected));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean isGeneratedCommonActive(@Nonnull PendingCommonObservation pending) {
        CommonAsset asset = CommonAssetRegistry.getByName(pending.commonName());
        if (!(asset instanceof FileCommonAsset fileAsset)) {
            return false;
        }
        if (!pending.expectedHash().equals(asset.getHash())) {
            return false;
        }
        Path activePath = fileAsset.getFile().toAbsolutePath().normalize();
        if (activePath.equals(pending.generatedPath())) {
            return true;
        }
        try {
            return Files.isSameFile(activePath, pending.generatedPath());
        } catch (IOException ex) {
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

    private record PendingCommonObservation(long sequence,
                                            @Nonnull String target,
                                            @Nonnull String commonName,
                                            @Nonnull Path generatedPath,
                                            @Nonnull String expectedHash) {
        @Nonnull
        PendingCommonObservation withSequence(long newSequence) {
            return new PendingCommonObservation(newSequence, target, commonName, generatedPath, expectedHash);
        }
    }
}
