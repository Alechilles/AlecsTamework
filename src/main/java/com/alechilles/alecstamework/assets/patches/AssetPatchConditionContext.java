package com.alechilles.alecstamework.assets.patches;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Installed asset-pack view used to evaluate optional patch conditions.
 */
public final class AssetPatchConditionContext {
    private static final Gson GSON = new Gson();

    private final Set<String> installedPacks;
    private final List<PackInfo> packs;
    private final Map<String, JsonElement> settings;
    private final String serverVersion;

    public AssetPatchConditionContext(@Nonnull String generatedPackId,
                                      @Nonnull Iterable<String> installedPacks) {
        List<PackInfo> packInfos = new ArrayList<>();
        for (String pack : installedPacks) {
            if (pack == null) {
                continue;
            }
            String id = pack.trim();
            if (!id.isBlank()) {
                packInfos.add(new PackInfo(id, null, null));
            }
        }
        this.packs = filteredPacks(generatedPackId, packInfos);
        this.installedPacks = installedPackIds(this.packs);
        this.settings = settingsByPath(TameworkSettingsStore.defaultGlobalSettings());
        this.serverVersion = null;
    }

    public AssetPatchConditionContext(@Nonnull String generatedPackId,
                                      @Nonnull Collection<PackInfo> packs,
                                      @Nonnull ResolvedTameworkSettings settings,
                                      @Nullable String serverVersion) {
        this.packs = filteredPacks(generatedPackId, packs);
        this.installedPacks = installedPackIds(this.packs);
        this.settings = settingsByPath(settings);
        this.serverVersion = trimToNull(serverVersion);
    }

    public boolean hasInstalledPack(@Nonnull String packId) {
        return installedPacks.contains(packId);
    }

    @Nullable
    public String installedPackVersion(@Nonnull String packId) {
        String version = null;
        for (PackInfo pack : packs) {
            if (pack.id().equals(packId)) {
                version = pack.version();
            }
        }
        return version;
    }

    @Nullable
    public String serverVersion() {
        return serverVersion;
    }

    public boolean assetExists(@Nullable String assetPath) {
        String normalized = normalizeAssetPath(assetPath);
        if (normalized == null) {
            return false;
        }
        for (PackInfo pack : packs) {
            Path root = pack.root();
            if (root != null && Files.isRegularFile(root.resolve(normalized))) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    public Optional<JsonElement> jsonPathValue(@Nullable String assetPath, @Nonnull String jsonPointer) {
        String normalized = normalizeAssetPath(assetPath);
        if (normalized == null) {
            return Optional.empty();
        }
        JsonElement document = readWinningJson(normalized);
        if (document == null) {
            return Optional.empty();
        }
        return readJsonPointer(document, jsonPointer);
    }

    @Nonnull
    public Optional<JsonElement> settingValue(@Nonnull String path) {
        String key = normalizeSettingPath(path);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(settings.get(key));
    }

    @Nonnull
    static PackInfo packInfo(@Nonnull String id, @Nullable Path root) {
        return new PackInfo(id, root, readPackVersion(root));
    }

    @Nonnull
    static ResolvedTameworkSettings runtimeSettings() {
        return TameworkSettingsStore.loadRuntimeGlobalSettings();
    }

    @Nullable
    static String runtimeServerVersion() {
        String property = trimToNull(System.getProperty("hytale.server.version"));
        if (property != null) {
            return property;
        }
        property = trimToNull(System.getProperty("hytale.game.version"));
        if (property != null) {
            return property;
        }
        try (Reader reader = new InputStreamReader(
                AssetPatchConditionContext.class.getClassLoader().getResourceAsStream("manifest.json"),
                StandardCharsets.UTF_8
        )) {
            JsonElement manifest = JsonParser.parseReader(reader);
            if (manifest != null && manifest.isJsonObject()) {
                return readManifestString(manifest.getAsJsonObject(), "ServerVersion");
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    @Nonnull
    private static List<PackInfo> filteredPacks(@Nonnull String generatedPackId,
                                                @Nonnull Iterable<PackInfo> source) {
        List<PackInfo> result = new ArrayList<>();
        for (PackInfo pack : source) {
            if (pack == null) {
                continue;
            }
            String id = trimToNull(pack.id());
            if (id != null && !generatedPackId.equals(id)) {
                result.add(new PackInfo(id, pack.root(), trimToNull(pack.version())));
            }
        }
        return List.copyOf(result);
    }

    @Nonnull
    private static Set<String> installedPackIds(@Nonnull Iterable<PackInfo> packs) {
        Set<String> ids = new LinkedHashSet<>();
        for (PackInfo pack : packs) {
            ids.add(pack.id());
        }
        return Set.copyOf(ids);
    }

    @Nullable
    private JsonElement readWinningJson(@Nonnull String assetPath) {
        Path winningSource = null;
        for (PackInfo pack : packs) {
            Path root = pack.root();
            if (root == null) {
                continue;
            }
            Path candidate = root.resolve(assetPath);
            if (Files.isRegularFile(candidate)) {
                winningSource = candidate;
            }
        }
        if (winningSource == null) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(winningSource, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    private static Optional<JsonElement> readJsonPointer(@Nonnull JsonElement document,
                                                         @Nonnull String pointer) {
        if (pointer.isEmpty()) {
            return Optional.of(document);
        }
        if (!pointer.startsWith("/")) {
            return Optional.empty();
        }
        JsonElement current = document;
        String[] parts = pointer.substring(1).split("/", -1);
        for (String rawPart : parts) {
            String part = rawPart.replace("~1", "/").replace("~0", "~");
            if (current == null || current.isJsonNull()) {
                return Optional.empty();
            }
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(part);
            } else if (current.isJsonArray()) {
                Integer index = parseArrayIndex(part);
                if (index == null || index < 0 || index >= current.getAsJsonArray().size()) {
                    return Optional.empty();
                }
                current = current.getAsJsonArray().get(index);
            } else {
                return Optional.empty();
            }
        }
        return current == null ? Optional.empty() : Optional.of(current);
    }

    @Nullable
    private static Integer parseArrayIndex(@Nonnull String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Nullable
    private static String readPackVersion(@Nullable Path root) {
        if (root == null) {
            return null;
        }
        Path manifest = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonObject object = GSON.fromJson(reader, JsonObject.class);
            if (object == null) {
                return null;
            }
            String version = readManifestString(object, "Version");
            return version == null ? readManifestString(object, "version") : version;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static String readManifestString(@Nonnull JsonObject object, @Nonnull String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return trimToNull(element.getAsString());
    }

    @Nullable
    private static String normalizeAssetPath(@Nullable String assetPath) {
        String trimmed = trimToNull(assetPath);
        if (trimmed == null) {
            return null;
        }
        return AssetPatchDefinition.normalizeAssetPath(trimmed);
    }

    @Nonnull
    private static Map<String, JsonElement> settingsByPath(@Nonnull ResolvedTameworkSettings settings) {
        Map<String, JsonElement> values = new LinkedHashMap<>();
        put(values, "population.limitPerPlayerOwnedTotal", settings.populationLimitPerPlayerOwnedTotal());
        put(values, "population.perPlayerLimitScope", settings.populationPerPlayerLimitScope());
        put(values, "simpleClaims.enabled", settings.simpleClaimsEnabled());
        put(values, "simpleClaims.limitPerClaimChunk", settings.simpleClaimsLimitPerClaimChunk());
        put(values, "simpleClaims.limitPerClaimTotal", settings.simpleClaimsLimitPerClaimTotal());
        put(values, "simpleClaims.breedingRequiresClaim", settings.simpleClaimsBreedingRequiresClaim());
        put(values, "simpleClaims.protectTamedFromNonMembers", settings.simpleClaimsProtectTamedFromNonMembers());
        put(values, "ownership.blockOwnerDamage", settings.blockOwnerDamage());
        put(values, "ownership.blockAllPlayerDamageIfOwned", settings.blockAllPlayerDamageIfOwned());
        put(values, "ownership.invulnerableIfOwned", settings.invulnerableIfOwned());
        put(values, "spawner.captureClearsOwner", settings.captureClearsOwner());
        put(values, "spawner.spawnSetsOwner", settings.spawnSetsOwner());
        put(values, "ownership.captureRequiresOwner", settings.captureRequiresOwner());
        put(values, "ownership.spawnRequiresOwner", settings.spawnRequiresOwner());
        put(values, "ownership.interactionRequiresOwner", settings.interactionRequiresOwner());
        put(values, "ownership.linkingRequiresOwner", settings.linkingRequiresOwner());
        put(values, "needs.enabled", settings.needsEnabled());
        put(values, "needs.tickPolicyMode", settings.needsTickPolicyMode());
        put(values, "needs.ownerOfflineGraceHours", settings.needsOwnerOfflineGraceHours());
        put(values, "needs.ownerOfflineDecayMultiplier", settings.needsOwnerOfflineDecayMultiplier());
        put(values, "needs.damage.enabled", settings.needsDamageEnabled());
        put(values, "needs.damage.model", settings.needsDamageModel());
        put(values, "needs.damage.dualNeedRule", settings.needsDamageDualNeedRule());
        put(values, "needs.damage.starvationDamagePerMinute", settings.needsStarvationDamagePerMinute());
        put(values, "needs.damage.dehydrationDamagePerMinute", settings.needsDehydrationDamagePerMinute());
        put(values, "needs.damage.lethal", settings.needsDamageLethal());
        put(values, "happiness.enabled", settings.happinessEnabled());
        put(values, "breeding.passiveEnabled", settings.passiveBreedingEnabled());
        put(values, "breeding.requiresHappiness", settings.breedingRequiresHappiness());
        put(values, "breeding.genderEnabled", settings.breedingGenderEnabled());
        put(values, "traits.enabled", settings.traitsEnabled());
        put(values, "progression.levelingEnabled", settings.levelingEnabled());
        put(values, "progression.talentsEnabled", settings.talentsEnabled());
        put(values, "revive.enabled", settings.reviveSystemEnabled());
        put(values, "travel.recallTeleportingEnabled", settings.recallTeleportingEnabled());
        put(values, "telemetry.enabled", settings.telemetryEnabled());
        put(values, "telemetry.breadcrumbsEnabled", settings.telemetryBreadcrumbsEnabled());
        return Map.copyOf(values);
    }

    private static void put(@Nonnull Map<String, JsonElement> values, @Nonnull String path, boolean value) {
        JsonElement element = new JsonPrimitive(value);
        values.put(normalizeSettingPath(path), element);
        values.put(normalizeSettingPath(toRecordName(path)), element);
    }

    private static void put(@Nonnull Map<String, JsonElement> values, @Nonnull String path, int value) {
        JsonElement element = new JsonPrimitive(value);
        values.put(normalizeSettingPath(path), element);
        values.put(normalizeSettingPath(toRecordName(path)), element);
    }

    private static void put(@Nonnull Map<String, JsonElement> values, @Nonnull String path, double value) {
        JsonElement element = new JsonPrimitive(value);
        values.put(normalizeSettingPath(path), element);
        values.put(normalizeSettingPath(toRecordName(path)), element);
    }

    private static void put(@Nonnull Map<String, JsonElement> values, @Nonnull String path, @Nonnull String value) {
        JsonElement element = new JsonPrimitive(value);
        values.put(normalizeSettingPath(path), element);
        values.put(normalizeSettingPath(toRecordName(path)), element);
    }

    @Nonnull
    private static String toRecordName(@Nonnull String path) {
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return builder.toString();
    }

    @Nullable
    private static String normalizeSettingPath(@Nullable String path) {
        String trimmed = trimToNull(path);
        if (trimmed == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /**
     * Metadata needed by condition evaluation for one registered pack.
     */
    public record PackInfo(@Nonnull String id, @Nullable Path root, @Nullable String version) {
    }
}
