package com.alechilles.alecstamework.config.overrides;

import com.alechilles.alecstamework.Tamework;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetLoadResult;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Discovers, applies, and reloads Tamework local override JSONs.
 */
public final class TwConfigOverrideManager {
    public static final String OVERRIDE_ROOT_RELATIVE = "Tamework";

    private static final String SNAPSHOT_DIR_NAME = "_snapshots";
    private static final String STAGING_DIR_NAME = "_staging";
    private static final String OVERRIDE_PACK_PREFIX = "tamework-overrides::";
    private static final int BACKUP_RETENTION = 10;
    private static final int STAGING_RELOAD_RETENTION = 10;
    private static final DateTimeFormatter SNAPSHOT_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final Tamework plugin;
    private final Object reloadLock = new Object();
    private final Set<String> loadedOverrideSourcePackKeys = new HashSet<>();
    private final Map<String, Path> canonicalSourcePathsByDescriptorKey = new ConcurrentHashMap<>();

    public TwConfigOverrideManager(@Nonnull Tamework plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Nonnull
    public Path resolveOverrideRoot(@Nonnull World world) {
        return resolveUniverseRoot(world).resolve(OVERRIDE_ROOT_RELATIVE).normalize();
    }

    @Nonnull
    public String resolveOverrideScopeKey(@Nonnull World world) {
        return normalizeScopeKey(resolveOverrideRoot(world));
    }

    @Nonnull
    public Path resolveOverridePath(@Nonnull World world, @Nonnull TwConfigAssetDescriptor descriptor) {
        Path packRoot = resolveOverrideRoot(world).resolve(packDirectoryName(descriptor.sourcePackKey()));
        return resolvePortable(packRoot, overrideRelativePath(descriptor)).normalize();
    }

    @Nonnull
    public JsonObject readSourceJson(@Nonnull TwConfigAssetDescriptor descriptor) {
        return TwConfigJsonUtil.readObjectOrEmpty(descriptor.sourcePath());
    }

    @Nonnull
    public JsonObject readOverrideJson(@Nonnull World world, @Nonnull TwConfigAssetDescriptor descriptor) {
        Path overridePath = resolveOverridePath(world, descriptor);
        return TwConfigJsonUtil.readObjectOrEmpty(overridePath);
    }

    @Nonnull
    private Path resolveUniverseRoot(@Nonnull World world) {
        return resolveUniverseRoot(world.getSavePath());
    }

    @Nonnull
    static Path resolveUniverseRoot(@Nonnull Path savePath) {
        Path normalizedSavePath = savePath.toAbsolutePath().normalize();
        Path cursor = normalizedSavePath;
        while (cursor != null) {
            Path fileName = cursor.getFileName();
            if (fileName != null && "universe".equalsIgnoreCase(fileName.toString())) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        Path parent = normalizedSavePath.getParent();
        if (parent != null
                && parent.getFileName() != null
                && "worlds".equalsIgnoreCase(parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent();
        }
        return normalizedSavePath;
    }

    @Nonnull
    static String normalizeScopeKey(@Nonnull Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private Path overrideRelativePath(@Nonnull TwConfigAssetDescriptor descriptor) {
        Path familyPath = tameworkRelativeStorePath(descriptor.storePath());
        String fileName = overrideFileName(descriptor);
        if (familyPath.toString().isBlank()) {
            return Path.of(fileName);
        }
        return familyPath.resolve(fileName);
    }

    @Nonnull
    private Path tameworkRelativeStorePath(@Nullable String storePath) {
        if (storePath == null || storePath.isBlank()) {
            return Path.of("");
        }
        String normalized = storePath.replace('\\', '/').trim();
        if ("tamework".equalsIgnoreCase(normalized)) {
            return Path.of("");
        }
        String prefix = "tamework/";
        if (normalized.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            normalized = normalized.substring(prefix.length());
        }
        return normalized.isBlank() ? Path.of("") : Path.of(normalized);
    }

    @Nonnull
    private String overrideFileName(@Nonnull TwConfigAssetDescriptor descriptor) {
        String fileName = sanitizeFileName(descriptor.assetId()) + ".json";
        if (descriptor.relativeServerPath() != null && descriptor.relativeServerPath().getFileName() != null) {
            fileName = descriptor.relativeServerPath().getFileName().toString();
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            fileName = fileName + ".json";
        }
        return fileName;
    }

    @Nonnull
    private String packDirectoryName(@Nullable String sourcePackKey) {
        String raw = sourcePackKey == null ? "<unknown-pack>" : sourcePackKey.trim();
        String candidate = raw;
        int colonIndex = raw.indexOf(':');
        if (colonIndex >= 0 && colonIndex < raw.length() - 1) {
            candidate = raw.substring(colonIndex + 1).trim();
        }
        if (candidate.isBlank()) {
            candidate = raw;
        }
        return sanitizeDirectoryName(candidate);
    }

    @Nonnull
    private String sanitizeDirectoryName(@Nonnull String value) {
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        while (!sanitized.isBlank() && (sanitized.endsWith(".") || sanitized.endsWith(" "))) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized.isBlank() ? "UnknownPack" : sanitized;
    }

    @Nonnull
    public TwConfigSnapshot createSnapshot(@Nonnull World world) {
        List<StoreBinding> bindings = discoverStoreBindings();
        List<TwConfigAssetDescriptor> descriptors = discoverDescriptors(bindings);
        LinkedHashMap<String, TwConfigSnapshot.FamilyView> families = new LinkedHashMap<>();
        for (StoreBinding binding : bindings) {
            families.put(
                    binding.familyKey,
                    new TwConfigSnapshot.FamilyView(
                            binding.familyKey,
                            binding.familyLabel,
                            binding.editable,
                            binding.knownType
                    )
            );
        }
        String baselineHash = computeBaselineHash(world, descriptors);
        return new TwConfigSnapshot(resolveOverrideRoot(world), baselineHash, descriptors, families);
    }

    @Nonnull
    public ReloadResult reloadOverrides(@Nonnull World world) {
        synchronized (reloadLock) {
            long reloadStartedNanos = System.nanoTime();
            plugin.getLogger().at(Level.INFO).log(
                    "TwConfig reload started for world " + world.getName() + "."
            );
            List<StoreBinding> bindings = discoverStoreBindings();
            LinkedHashMap<String, StoreBinding> bindingByFamilyKey = new LinkedHashMap<>();
            for (StoreBinding binding : bindings) {
                bindingByFamilyKey.put(binding.familyKey, binding);
            }
            TwConfigSnapshot snapshot = createSnapshot(world);
            Path overrideRoot = resolveOverrideRoot(world);
            Path stagingParent = overrideRoot.resolve(STAGING_DIR_NAME).normalize();
            Path stagingRoot = stagingParent
                    .resolve("reload-" + UUID.randomUUID())
                    .normalize();
            ArrayList<String> errors = new ArrayList<>();
            LinkedHashMap<LoadTarget, Integer> stagedTargets = new LinkedHashMap<>();
            LinkedHashSet<String> stagedPackKeys = new LinkedHashSet<>();
            int loadedDirectories = 0;
            int loadedPacks = 0;

            try {
                Files.createDirectories(stagingRoot);
            } catch (IOException ex) {
                plugin.getLogger().at(Level.WARNING).withCause(ex).log("Failed creating override staging root.");
                return new ReloadResult(0, 0, List.of("Failed creating override staging root."));
            }

            LinkedHashSet<String> refreshPackKeys = new LinkedHashSet<>(loadedOverrideSourcePackKeys);
            for (TwConfigAssetDescriptor descriptor : snapshot.getDescriptors()) {
                JsonObject overrideJson = readOverrideJson(world, descriptor);
                if (!TwConfigJsonUtil.isEmptyObject(overrideJson)) {
                    refreshPackKeys.add(descriptor.sourcePackKey());
                }
            }

            for (TwConfigAssetDescriptor descriptor : snapshot.getDescriptors()) {
                if (!refreshPackKeys.contains(descriptor.sourcePackKey())) {
                    continue;
                }
                StoreBinding binding = bindingByFamilyKey.get(descriptor.familyKey());
                if (binding == null || binding.store == null) {
                    errors.add("No asset store binding found for family " + descriptor.familyDisplayName() + ".");
                    continue;
                }
                JsonObject sourceJson = readSourceJson(descriptor);
                JsonObject overrideJson = readOverrideJson(world, descriptor);
                if (TwConfigJsonUtil.isEmptyObject(sourceJson) && TwConfigJsonUtil.isEmptyObject(overrideJson)) {
                    continue;
                }
                JsonObject mergedForLoad = TwConfigJsonUtil.merge(sourceJson, overrideJson);
                Path stagedPackRoot = stagingRoot.resolve(encodePackKey(descriptor.sourcePackKey()));
                Path stagedPath = resolvePortable(stagedPackRoot, descriptor.relativeServerPath()).normalize();
                try {
                    TwConfigJsonUtil.writeObjectAtomic(stagedPath, mergedForLoad);
                    stagedTargets.merge(
                            new LoadTarget(binding.familyKey, descriptor.sourcePackKey()),
                            1,
                            Integer::sum
                    );
                    stagedPackKeys.add(descriptor.sourcePackKey());
                } catch (IOException ex) {
                    errors.add("Failed staging merged override for " + descriptor.assetId() + ".");
                    plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                            "Failed staging merged override for family %s asset %s pack %s.",
                            descriptor.familyDisplayName(),
                            descriptor.assetId(),
                            descriptor.sourcePackKey()
                    );
                }
            }

            plugin.getLogger().at(Level.INFO).log(
                    "TwConfig reload staged targets=" + stagedTargets.size()
                            + " packs=" + stagedPackKeys.size()
                            + " elapsed=" + elapsedMillis(reloadStartedNanos) + "ms."
            );

            LinkedHashSet<String> loadedPackKeys = new LinkedHashSet<>();
            plugin.beginOverrideAssetEventSuppression();
            plugin.beginItemFeatureAssetReloadSuppression();
            try {
                // Do not call removeAssetPack here. On some runtimes that path can deadlock the world thread.
                // Instead, refresh active override packs by loading a fully-materialized merged view per descriptor.

                for (LoadTarget target : stagedTargets.keySet()) {
                    StoreBinding binding = bindingByFamilyKey.get(target.familyKey());
                    if (binding == null || binding.store == null) {
                        continue;
                    }
                    Path familyRoot = stagingRoot
                            .resolve(encodePackKey(target.sourcePackKey()))
                            .resolve("Server")
                            .resolve(binding.storePath);
                    if (!containsJsonFiles(familyRoot)) {
                        continue;
                    }
                    String overridePackKey = toOverridePackKey(target.sourcePackKey());
                    plugin.getLogger().at(Level.INFO).log(
                            "TwConfig reload loading family=" + binding.familyLabel
                                    + " pack=" + target.sourcePackKey()
                                    + " elapsed=" + elapsedMillis(reloadStartedNanos) + "ms."
                    );
                    try {
                        @SuppressWarnings("rawtypes")
                        AssetLoadResult result = binding.store.loadAssetsFromDirectory(overridePackKey, familyRoot);
                        loadedDirectories++;
                        loadedPackKeys.add(target.sourcePackKey());
                        if (result != null && result.hasFailed()) {
                            String detail = "Reload failed for family "
                                    + binding.familyLabel
                                    + " pack="
                                    + target.sourcePackKey()
                                    + " keys="
                                    + result.getFailedToLoadKeys().size()
                                    + " paths="
                                    + result.getFailedToLoadPaths().size();
                            errors.add(detail);
                            plugin.getLogger().at(Level.WARNING).log(detail);
                        }
                        plugin.getLogger().at(Level.INFO).log(
                                "TwConfig reload loaded family=" + binding.familyLabel
                                        + " pack=" + target.sourcePackKey()
                                        + " elapsed=" + elapsedMillis(reloadStartedNanos) + "ms."
                        );
                    } catch (Exception ex) {
                        String detail = "Reload exception for family "
                                + binding.familyLabel
                                + " pack="
                                + target.sourcePackKey();
                        errors.add(detail);
                        plugin.getLogger().at(Level.WARNING).withCause(ex).log(detail);
                    }
                }
            } finally {
                plugin.endItemFeatureAssetReloadSuppression();
                plugin.endOverrideAssetEventSuppression();
            }

            loadedPacks = loadedPackKeys.isEmpty() ? stagedPackKeys.size() : loadedPackKeys.size();
            loadedOverrideSourcePackKeys.clear();
            loadedOverrideSourcePackKeys.addAll(stagedPackKeys);
            try {
                pruneReloadStagingDirectories(stagingParent, STAGING_RELOAD_RETENTION);
            } catch (IOException ex) {
                plugin.getLogger().at(Level.WARNING).withCause(ex).log("Failed pruning override staging directories.");
            }
            plugin.getLogger().at(Level.INFO).log(
                    "TwConfig reload finished: loadedDirs=" + loadedDirectories
                            + ", loadedPacks=" + loadedPacks
                            + ", errors=" + errors.size()
                            + ", elapsed=" + elapsedMillis(reloadStartedNanos) + "ms."
            );
            return new ReloadResult(loadedDirectories, loadedPacks, errors);
        }
    }

    @Nonnull
    public ApplyResult applyDraft(@Nonnull World world,
                                  @Nonnull TwConfigSnapshot snapshot,
                                  @Nonnull Map<TwConfigAssetDescriptor, JsonObject> draftedOverrides,
                                  @Nullable TwConfigAssetDescriptor blankFileDescriptor) {
        long applyStartedNanos = System.nanoTime();
        plugin.getLogger().at(Level.INFO).log(
                "TwConfig apply started: draftedDescriptors=" + draftedOverrides.size()
        );
        String currentBaselineHash = computeBaselineHash(world, snapshot.getDescriptors());
        if (!Objects.equals(snapshot.getBaselineHash(), currentBaselineHash)) {
            plugin.getLogger().at(Level.INFO).log(
                    "TwConfig apply aborted by stale hash guard after " + elapsedMillis(applyStartedNanos) + "ms."
            );
            return ApplyResult.stale(
                    "Config files changed on disk since this editor session opened. Refresh and retry."
            );
        }

        LinkedHashMap<TwConfigAssetDescriptor, JsonObject> changed = new LinkedHashMap<>();
        for (Map.Entry<TwConfigAssetDescriptor, JsonObject> entry : draftedOverrides.entrySet()) {
            TwConfigAssetDescriptor descriptor = entry.getKey();
            if (descriptor == null || !descriptor.editable()) {
                continue;
            }
            JsonObject draft = TwConfigJsonUtil.copyObject(entry.getValue());
            JsonObject disk = readOverrideJson(world, descriptor);
            if (!draft.equals(disk)) {
                changed.put(descriptor, draft);
            }
        }

        if (changed.isEmpty()) {
            if (blankFileDescriptor != null && blankFileDescriptor.editable()) {
                Path blankPath = resolveOverridePath(world, blankFileDescriptor);
                if (!Files.exists(blankPath)) {
                    try {
                        TwConfigJsonUtil.writeObjectAtomic(blankPath, new JsonObject());
                        plugin.getLogger().at(Level.INFO).log(
                                "TwConfig apply created blank override file for asset "
                                        + blankFileDescriptor.assetId()
                                        + " at "
                                        + blankPath
                                        + "."
                        );
                        return ApplyResult.success(
                                "Created blank override file for " + blankFileDescriptor.assetId() + ".",
                                ReloadResult.empty()
                        );
                    } catch (IOException ex) {
                        plugin.getLogger().at(Level.WARNING).withCause(ex).log(
                                "TwConfig apply failed creating blank override file for asset %s.",
                                blankFileDescriptor.assetId()
                        );
                        return ApplyResult.validationFailed("Could not create blank override file.");
                    }
                }
            }
            plugin.getLogger().at(Level.INFO).log(
                    "TwConfig apply found no changed drafts after " + elapsedMillis(applyStartedNanos) + "ms."
            );
            return ApplyResult.success("No changes to apply.", ReloadResult.empty());
        }

        plugin.getLogger().at(Level.INFO).log(
                "TwConfig apply writing " + changed.size() + " changed override file(s)."
        );
        Path overrideRoot = resolveOverrideRoot(world);
        Path backupSnapshot;
        try {
            backupSnapshot = createBackupSnapshot(overrideRoot);
            plugin.getLogger().at(Level.INFO).log(
                    "TwConfig apply backup snapshot created in " + elapsedMillis(applyStartedNanos) + "ms."
            );
        } catch (IOException backupFailure) {
            plugin.getLogger().at(Level.WARNING).withCause(backupFailure).log(
                    "Failed to create override backup snapshot before apply."
            );
            return ApplyResult.validationFailed("Could not create rollback snapshot. Apply aborted.");
        }

        try {
            writeDraftToDisk(world, changed);
            cleanupEmptyOverrideDirectories(overrideRoot);
            plugin.getLogger().at(Level.INFO).log(
                    "TwConfig apply draft files written in " + elapsedMillis(applyStartedNanos) + "ms; reloading overrides."
            );
            ReloadResult reloadResult = reloadOverrides(world);
            if (reloadResult.hasErrors()) {
                restoreBackupSnapshot(overrideRoot, backupSnapshot);
                ReloadResult rollbackReload = reloadOverrides(world);
                plugin.getLogger().at(Level.WARNING).log(
                        "TwConfig apply reload failed and rolled back after " + elapsedMillis(applyStartedNanos) + "ms."
                );
                return ApplyResult.failed(
                        "Reload failed after writing overrides. Restored previous snapshot.",
                        reloadResult,
                        rollbackReload
                );
            }
            plugin.getLogger().at(Level.INFO).log(
                    "TwConfig apply completed successfully in " + elapsedMillis(applyStartedNanos) + "ms."
            );
            return ApplyResult.success("Applied " + changed.size() + " config override file(s).", reloadResult);
        } catch (Exception applyFailure) {
            plugin.getLogger().at(Level.WARNING).withCause(applyFailure).log(
                    "Override apply failed; attempting rollback."
            );
            try {
                restoreBackupSnapshot(overrideRoot, backupSnapshot);
                reloadOverrides(world);
            } catch (Exception rollbackFailure) {
                plugin.getLogger().at(Level.SEVERE).withCause(rollbackFailure).log(
                        "Rollback failed after override apply error."
                );
            }
            plugin.getLogger().at(Level.WARNING).log(
                    "TwConfig apply failed and rolled back after " + elapsedMillis(applyStartedNanos) + "ms."
            );
            return ApplyResult.failed("Apply failed and was rolled back.", ReloadResult.empty(), ReloadResult.empty());
        }
    }

    @Nonnull
    private List<StoreBinding> discoverStoreBindings() {
        ArrayList<StoreBinding> known = new ArrayList<>();
        Set<String> knownPaths = new HashSet<>();

        for (TwConfigFamily family : TwConfigFamily.values()) {
            if (family == TwConfigFamily.OTHER) {
                continue;
            }
            AssetStore<String, ?, ? extends AssetMap<String, ?>> store = family.getAssetStore();
            if (store == null) {
                continue;
            }
            known.add(new StoreBinding(
                    family,
                    family.getId(),
                    family.getDisplayName(),
                    family.getStorePath(),
                    family.isEditableInV1(),
                    family.isKnownType(),
                    store
            ));
            knownPaths.add(family.getStorePath().toLowerCase(Locale.ROOT));
        }

        ArrayList<StoreBinding> unknown = new ArrayList<>();
        Map<Class<? extends com.hypixel.hytale.assetstore.map.JsonAssetWithMap>, AssetStore<?, ?, ?>> storeMap =
                AssetRegistry.getStoreMap();
        for (AssetStore<?, ?, ?> rawStore : storeMap.values()) {
            if (rawStore == null) {
                continue;
            }
            String storePath = rawStore.getPath();
            if (storePath == null || storePath.isBlank()) {
                continue;
            }
            String normalizedPath = storePath.trim().toLowerCase(Locale.ROOT);
            if (!normalizedPath.startsWith("tamework/")) {
                continue;
            }
            if (knownPaths.contains(normalizedPath)) {
                continue;
            }
            String familyKey = "other:" + normalizedPath;
            if (containsFamilyKey(unknown, familyKey)) {
                continue;
            }
            unknown.add(new StoreBinding(
                    TwConfigFamily.OTHER,
                    familyKey,
                    "Other (" + storePath + ")",
                    storePath,
                    false,
                    false,
                    rawStore
            ));
        }

        unknown.sort(Comparator.comparing(binding -> binding.familyLabel, String.CASE_INSENSITIVE_ORDER));
        known.addAll(unknown);
        return known;
    }

    private boolean containsFamilyKey(@Nonnull Collection<StoreBinding> bindings, @Nonnull String familyKey) {
        for (StoreBinding binding : bindings) {
            if (binding.familyKey.equalsIgnoreCase(familyKey)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private List<TwConfigAssetDescriptor> discoverDescriptors(@Nonnull List<StoreBinding> bindings) {
        ArrayList<TwConfigAssetDescriptor> descriptors = new ArrayList<>();
        for (StoreBinding binding : bindings) {
            if (binding.store == null) {
                continue;
            }
            AssetMap<?, ?> assetMap = binding.store.getAssetMap();
            if (assetMap == null || assetMap.getAssetMap() == null || assetMap.getAssetMap().isEmpty()) {
                continue;
            }
            for (Map.Entry<?, ?> entry : assetMap.getAssetMap().entrySet()) {
                Object key = entry.getKey();
                if (key == null) {
                    continue;
                }
                String assetId = String.valueOf(key);
                if (assetId.isBlank()) {
                    continue;
                }
                String rawSourcePackKey = resolveSourcePackKey(assetMap, key);
                String sourcePackKey = normalizeSourcePackKey(rawSourcePackKey);
                Path discoveredSourcePath = resolveSourcePath(assetMap, key);
                String descriptorKey = descriptorKey(binding.familyKey, sourcePackKey, assetId);
                Path sourcePath = discoveredSourcePath;
                if (isOverridePackKey(rawSourcePackKey)) {
                    Path cachedSourcePath = canonicalSourcePathsByDescriptorKey.get(descriptorKey);
                    if (cachedSourcePath != null) {
                        sourcePath = cachedSourcePath;
                    }
                } else if (discoveredSourcePath != null) {
                    canonicalSourcePathsByDescriptorKey.put(descriptorKey, discoveredSourcePath);
                }
                Object asset = entry.getValue();
                String parentAssetId = resolveParentAssetId(asset);
                Path relativeServerPath = resolveRelativeServerPath(sourcePath, binding.storePath, assetId);
                descriptors.add(new TwConfigAssetDescriptor(
                        binding.family,
                        binding.familyKey,
                        binding.familyLabel,
                        binding.storePath,
                        assetId,
                        sourcePackKey,
                        parentAssetId,
                        sourcePath,
                        relativeServerPath,
                        binding.editable,
                        binding.knownType
                ));
            }
        }
        descriptors.sort(Comparator
                .comparing(TwConfigAssetDescriptor::familyDisplayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TwConfigAssetDescriptor::assetId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TwConfigAssetDescriptor::sourcePackKey, String.CASE_INSENSITIVE_ORDER));
        return descriptors;
    }

    @Nonnull
    private String resolveSourcePackKey(@Nonnull AssetMap<?, ?> assetMap, @Nonnull Object key) {
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            AssetMap rawMap = assetMap;
            String packKey = String.valueOf(rawMap.getAssetPack(key));
            if (packKey != null && !packKey.isBlank() && !"null".equalsIgnoreCase(packKey)) {
                return packKey;
            }
        } catch (Exception ignored) {
        }
        return "<unknown-pack>";
    }

    @Nonnull
    static String normalizeSourcePackKey(@Nullable String sourcePackKey) {
        String current = sourcePackKey == null ? "<unknown-pack>" : sourcePackKey.trim();
        if (current.isBlank()) {
            return "<unknown-pack>";
        }
        current = decodePackKey(current);
        while (current.startsWith(OVERRIDE_PACK_PREFIX)) {
            current = current.substring(OVERRIDE_PACK_PREFIX.length());
        }
        return current.isBlank() ? "<unknown-pack>" : current;
    }

    private static boolean isOverridePackKey(@Nullable String sourcePackKey) {
        return sourcePackKey != null && sourcePackKey.startsWith(OVERRIDE_PACK_PREFIX);
    }

    @Nonnull
    private static String descriptorKey(@Nonnull String familyKey,
                                        @Nonnull String sourcePackKey,
                                        @Nonnull String assetId) {
        return familyKey.toLowerCase(Locale.ROOT)
                + "|"
                + sourcePackKey.toLowerCase(Locale.ROOT)
                + "|"
                + assetId.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private Path resolveSourcePath(@Nonnull AssetMap<?, ?> assetMap, @Nonnull Object key) {
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            AssetMap rawMap = assetMap;
            Object path = rawMap.getPath(key);
            return path instanceof Path resolvedPath ? resolvedPath : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private String resolveParentAssetId(@Nullable Object asset) {
        if (asset == null) {
            return null;
        }
        try {
            Object result = asset.getClass()
                    .getMethod("getParentIdForFallback")
                    .invoke(asset);
            if (result instanceof String parentId && !parentId.isBlank()) {
                return parentId;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Nonnull
    static Path resolveRelativeServerPath(@Nullable Path sourcePath,
                                          @Nonnull String storePath,
                                          @Nonnull String assetId) {
        if (sourcePath != null && sourcePath.getNameCount() > 0) {
            for (int i = sourcePath.getNameCount() - 1; i >= 0; i--) {
                String segment = sourcePath.getName(i).toString();
                if ("server".equalsIgnoreCase(segment)) {
                    return sourcePath.subpath(i, sourcePath.getNameCount());
                }
            }
        }
        String fileName = "Config_" + sanitizeFileName(assetId) + ".json";
        if (sourcePath != null && sourcePath.getFileName() != null) {
            fileName = sourcePath.getFileName().toString();
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            fileName = fileName + ".json";
        }
        return Path.of("Server").resolve(storePath).resolve(fileName);
    }

    @Nonnull
    private static String sanitizeFileName(@Nonnull String assetId) {
        String sanitized = assetId.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "Unknown" : sanitized;
    }

    @Nonnull
    private Path resolvePortable(@Nonnull Path base, @Nonnull Path relative) {
        Path resolved = base;
        for (Path segment : relative) {
            if (segment == null) {
                continue;
            }
            String part = segment.toString();
            if (part == null || part.isBlank() || ".".equals(part)) {
                continue;
            }
            resolved = resolved.resolve(part);
        }
        return resolved;
    }

    @Nonnull
    private String computeBaselineHash(@Nonnull World world,
                                       @Nonnull List<TwConfigAssetDescriptor> descriptors) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (TwConfigAssetDescriptor descriptor : descriptors) {
                appendDigest(digest, descriptor.descriptorKey());
                appendDigest(digest, hashFile(descriptor.sourcePath()));
                appendDigest(digest, hashFile(resolveOverridePath(world, descriptor)));
            }
            return toHex(digest.digest());
        } catch (Exception ex) {
            return UUID.randomUUID().toString();
        }
    }

    @Nonnull
    private String hashFile(@Nullable Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "missing";
        }
        try {
            long size = Files.size(path);
            long modified = Files.getLastModifiedTime(path).toMillis();
            return size + ":" + modified;
        } catch (Exception ex) {
            return "error";
        }
    }

    private void appendDigest(@Nonnull MessageDigest digest, @Nonnull String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    @Nonnull
    private String toHex(@Nonnull byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            builder.append(String.format("%02x", current));
        }
        return builder.toString();
    }

    private void writeDraftToDisk(@Nonnull World world,
                                  @Nonnull Map<TwConfigAssetDescriptor, JsonObject> changed) throws IOException {
        for (Map.Entry<TwConfigAssetDescriptor, JsonObject> entry : changed.entrySet()) {
            TwConfigAssetDescriptor descriptor = entry.getKey();
            JsonObject object = TwConfigJsonUtil.copyObject(entry.getValue());
            Path path = resolveOverridePath(world, descriptor);
            if (TwConfigJsonUtil.isEmptyObject(object)) {
                Files.deleteIfExists(path);
                continue;
            }
            TwConfigJsonUtil.writeObjectAtomic(path, object);
        }
    }

    @Nonnull
    private Path createBackupSnapshot(@Nonnull Path overrideRoot) throws IOException {
        Files.createDirectories(overrideRoot);
        Path snapshotsRoot = overrideRoot.resolve(SNAPSHOT_DIR_NAME);
        Files.createDirectories(snapshotsRoot);

        String snapshotName = SNAPSHOT_FORMAT.format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path snapshotDirectory = snapshotsRoot.resolve(snapshotName);
        Files.createDirectories(snapshotDirectory);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(overrideRoot)) {
            for (Path child : stream) {
                if (child == null || child.getFileName() == null) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (isControlDirectoryName(name)) {
                    continue;
                }
                copyRecursively(child, snapshotDirectory.resolve(name));
            }
        }

        pruneSnapshotBackups(snapshotsRoot, BACKUP_RETENTION);
        return snapshotDirectory;
    }

    private void restoreBackupSnapshot(@Nonnull Path overrideRoot, @Nullable Path snapshotDirectory) throws IOException {
        Files.createDirectories(overrideRoot);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(overrideRoot)) {
            for (Path child : stream) {
                if (child == null || child.getFileName() == null) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (isControlDirectoryName(name)) {
                    continue;
                }
                deleteRecursively(child);
            }
        }

        if (snapshotDirectory == null || !Files.isDirectory(snapshotDirectory)) {
            return;
        }
        try (DirectoryStream<Path> snapshotChildren = Files.newDirectoryStream(snapshotDirectory)) {
            for (Path child : snapshotChildren) {
                if (child == null || child.getFileName() == null) {
                    continue;
                }
                copyRecursively(child, overrideRoot.resolve(child.getFileName().toString()));
            }
        }
    }

    static void pruneSnapshotBackups(@Nullable Path snapshotsRoot, int retention) throws IOException {
        if (snapshotsRoot == null || !Files.isDirectory(snapshotsRoot)) {
            return;
        }
        int keep = Math.max(1, retention);
        ArrayList<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotsRoot)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    directories.add(child);
                }
            }
        }
        directories.sort(Comparator.comparing(Path::getFileName, Comparator.nullsLast(Comparator.reverseOrder())));
        for (int i = keep; i < directories.size(); i++) {
            deleteRecursively(directories.get(i));
        }
    }

    static void pruneReloadStagingDirectories(@Nullable Path stagingRoot, int retention) throws IOException {
        if (stagingRoot == null || !Files.isDirectory(stagingRoot)) {
            return;
        }
        int keep = Math.max(1, retention);
        ArrayList<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingRoot, "reload-*")) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    directories.add(child);
                }
            }
        }
        directories.sort(Comparator
                .comparingLong(TwConfigOverrideManager::lastModifiedMillis)
                .reversed()
                .thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        for (int i = keep; i < directories.size(); i++) {
            deleteRecursively(directories.get(i));
        }
    }

    private static long lastModifiedMillis(@Nonnull Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static void deleteRecursively(@Nonnull Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            List<Path> toDelete = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path candidate : toDelete) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static void copyRecursively(@Nonnull Path source, @Nonnull Path destination) throws IOException {
        if (Files.isRegularFile(source)) {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, destination);
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(source)) {
            for (Path current : walk.toList()) {
                Path relative = source.relativize(current);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(current)) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(current, target);
                }
            }
        }
    }

    private void cleanupEmptyOverrideDirectories(@Nonnull Path overrideRoot) throws IOException {
        if (!Files.isDirectory(overrideRoot)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(overrideRoot)) {
            List<Path> directories = walk.filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path directory : directories) {
                if (directory.equals(overrideRoot)) {
                    continue;
                }
                if (isControlPath(overrideRoot, directory)) {
                    continue;
                }
                try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                    if (!children.iterator().hasNext()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        }
    }

    private boolean isControlPath(@Nonnull Path overrideRoot, @Nonnull Path candidate) {
        Path relative;
        try {
            relative = overrideRoot.relativize(candidate);
        } catch (Exception ignored) {
            return false;
        }
        if (relative.getNameCount() == 0) {
            return false;
        }
        return isControlDirectoryName(relative.getName(0).toString());
    }

    @Nonnull
    private List<Path> listPackDirectories(@Nonnull Path overrideRoot) {
        if (!Files.isDirectory(overrideRoot)) {
            return List.of();
        }
        ArrayList<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(overrideRoot)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child) || child.getFileName() == null) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (isControlDirectoryName(name)) {
                    continue;
                }
                paths.add(child);
            }
        } catch (IOException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log("Failed to list override pack directories.");
        }
        paths.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return paths;
    }

    private boolean containsJsonFiles(@Nullable Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName() != null
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isControlDirectoryName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return name.startsWith("_")
                || SNAPSHOT_DIR_NAME.equalsIgnoreCase(name)
                || STAGING_DIR_NAME.equalsIgnoreCase(name);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    static String encodePackKey(@Nullable String sourcePackKey) {
        String value = sourcePackKey == null ? "<unknown-pack>" : sourcePackKey;
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String decodePackKey(@Nullable String encodedPackKey) {
        if (encodedPackKey == null || encodedPackKey.isBlank()) {
            return "<unknown-pack>";
        }
        try {
            return URLDecoder.decode(encodedPackKey, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return encodedPackKey;
        }
    }

    static String toOverridePackKey(@Nullable String sourcePackKey) {
        return OVERRIDE_PACK_PREFIX + (sourcePackKey == null ? "<unknown-pack>" : sourcePackKey);
    }

    private record LoadTarget(
            @Nonnull String familyKey,
            @Nonnull String sourcePackKey
    ) {
    }

    private record StoreBinding(
            @Nonnull TwConfigFamily family,
            @Nonnull String familyKey,
            @Nonnull String familyLabel,
            @Nonnull String storePath,
            boolean editable,
            boolean knownType,
            @Nonnull AssetStore<?, ?, ?> store
    ) {
    }

    public static final class ReloadResult {
        private static final ReloadResult EMPTY = new ReloadResult(0, 0, List.of());
        private final int loadedDirectories;
        private final int loadedPacks;
        private final List<String> errors;

        public ReloadResult(int loadedDirectories, int loadedPacks, @Nonnull List<String> errors) {
            this.loadedDirectories = loadedDirectories;
            this.loadedPacks = loadedPacks;
            this.errors = List.copyOf(errors);
        }

        @Nonnull
        public static ReloadResult empty() {
            return EMPTY;
        }

        public int getLoadedDirectories() {
            return loadedDirectories;
        }

        public int getLoadedPacks() {
            return loadedPacks;
        }

        @Nonnull
        public List<String> getErrors() {
            return errors;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    public static final class ApplyResult {
        private final boolean success;
        private final boolean stale;
        private final boolean validationFailed;
        private final String message;
        private final ReloadResult reloadResult;
        private final ReloadResult rollbackReloadResult;

        private ApplyResult(boolean success,
                            boolean stale,
                            boolean validationFailed,
                            @Nonnull String message,
                            @Nonnull ReloadResult reloadResult,
                            @Nonnull ReloadResult rollbackReloadResult) {
            this.success = success;
            this.stale = stale;
            this.validationFailed = validationFailed;
            this.message = message;
            this.reloadResult = reloadResult;
            this.rollbackReloadResult = rollbackReloadResult;
        }

        @Nonnull
        public static ApplyResult success(@Nonnull String message, @Nonnull ReloadResult reloadResult) {
            return new ApplyResult(true, false, false, message, reloadResult, ReloadResult.empty());
        }

        @Nonnull
        public static ApplyResult stale(@Nonnull String message) {
            return new ApplyResult(false, true, false, message, ReloadResult.empty(), ReloadResult.empty());
        }

        @Nonnull
        public static ApplyResult validationFailed(@Nonnull String message) {
            return new ApplyResult(false, false, true, message, ReloadResult.empty(), ReloadResult.empty());
        }

        @Nonnull
        public static ApplyResult failed(@Nonnull String message,
                                         @Nonnull ReloadResult reloadResult,
                                         @Nonnull ReloadResult rollbackReloadResult) {
            return new ApplyResult(false, false, false, message, reloadResult, rollbackReloadResult);
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isStale() {
            return stale;
        }

        public boolean isValidationFailed() {
            return validationFailed;
        }

        @Nonnull
        public String getMessage() {
            return message;
        }

        @Nonnull
        public ReloadResult getReloadResult() {
            return reloadResult;
        }

        @Nonnull
        public ReloadResult getRollbackReloadResult() {
            return rollbackReloadResult;
        }
    }
}
