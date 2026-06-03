package com.alechilles.alecstamework.assets.patches;

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
            Path candidate = resolvePackAsset(pack.root(), normalized);
            if (candidate != null && Files.isRegularFile(candidate)) {
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
            Path candidate = resolvePackAsset(pack.root(), assetPath);
            if (candidate != null && Files.isRegularFile(candidate)) {
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

    @Nullable
    private static Path resolvePackAsset(@Nullable Path root, @Nonnull String assetPath) {
        if (root == null) {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(assetPath).toAbsolutePath().normalize();
        return candidate.startsWith(normalizedRoot) ? candidate : null;
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
        put(values, settings.populationLimitPerPlayerOwnedTotal(),
                "populationLimitPerPlayerOwnedTotal", "population.limitPerPlayerOwnedTotal");
        put(values, settings.populationPerPlayerLimitScope(),
                "populationPerPlayerLimitScope", "population.perPlayerLimitScope");
        put(values, settings.simpleClaimsEnabled(),
                "simpleClaimsEnabled", "simpleClaims.simpleClaimsEnabled", "simpleClaims.enabled");
        put(values, settings.simpleClaimsLimitPerClaimChunk(),
                "simpleClaimsLimitPerClaimChunk", "simpleClaims.limitPerClaimChunk");
        put(values, settings.simpleClaimsLimitPerClaimTotal(),
                "simpleClaimsLimitPerClaimTotal", "simpleClaims.limitPerClaimTotal");
        put(values, settings.simpleClaimsBreedingRequiresClaim(),
                "simpleClaimsBreedingRequiresClaim", "simpleClaims.breedingRequiresClaim");
        put(values, settings.simpleClaimsProtectTamedFromNonMembers(),
                "simpleClaimsProtectTamedFromNonMembers", "simpleClaims.protectTamedFromNonMembers");
        put(values, settings.blockOwnerDamage(),
                "blockOwnerDamage", "ownership.damageProtection.blockOwnerDamage", "ownership.blockOwnerDamage");
        put(values, settings.blockAllPlayerDamageIfOwned(),
                "blockAllPlayerDamageIfOwned", "ownership.damageProtection.blockAllPlayerDamageIfOwned",
                "ownership.blockAllPlayerDamageIfOwned");
        put(values, settings.invulnerableIfOwned(),
                "invulnerableIfOwned", "ownership.damageProtection.invulnerableIfOwned",
                "ownership.invulnerableIfOwned");
        put(values, settings.captureClearsOwner(),
                "captureClearsOwner", "ownership.capture.captureClearsOwner", "ownership.captureClearsOwner",
                "spawner.captureClearsOwner");
        put(values, settings.spawnSetsOwner(),
                "spawnSetsOwner", "ownership.capture.spawnSetsOwner", "ownership.spawnSetsOwner",
                "spawner.spawnSetsOwner");
        put(values, settings.captureRequiresOwner(),
                "captureRequiresOwner", "ownership.capture.captureRequiresOwner", "ownership.captureRequiresOwner");
        put(values, settings.spawnRequiresOwner(),
                "spawnRequiresOwner", "ownership.capture.spawnRequiresOwner", "ownership.spawnRequiresOwner");
        put(values, settings.interactionRequiresOwner(),
                "interactionRequiresOwner", "ownership.interactionRequiresOwner");
        put(values, settings.linkingRequiresOwner(),
                "linkingRequiresOwner", "ownership.linkingRequiresOwner");
        put(values, settings.needsEnabled(),
                "needsEnabled", "needs.enabled");
        put(values, settings.needsTickPolicyMode(),
                "needsTickPolicyMode", "needs.tickPolicy.mode", "needs.tickPolicyMode");
        put(values, settings.needsOwnerOfflineGraceHours(),
                "needsOwnerOfflineGraceHours", "needs.tickPolicy.ownerOfflineGraceHours",
                "needs.ownerOfflineGraceHours");
        put(values, settings.needsOwnerOfflineDecayMultiplier(),
                "needsOwnerOfflineDecayMultiplier", "needs.tickPolicy.ownerOfflineDecayMultiplier",
                "needs.ownerOfflineDecayMultiplier");
        put(values, settings.needsDamageEnabled(),
                "needsDamageEnabled", "needs.damage.enabled");
        put(values, settings.needsDamageModel(),
                "needsDamageModel", "needs.damage.model");
        put(values, settings.needsDamageDualNeedRule(),
                "needsDamageDualNeedRule", "needs.damage.dualNeedRule");
        put(values, settings.needsStarvationDamagePerMinute(),
                "needsStarvationDamagePerMinute", "needs.damage.starvationDamagePerMinute");
        put(values, settings.needsDehydrationDamagePerMinute(),
                "needsDehydrationDamagePerMinute", "needs.damage.dehydrationDamagePerMinute");
        put(values, settings.needsDamageLethal(),
                "needsDamageLethal", "needs.damage.lethal");
        put(values, settings.happinessEnabled(),
                "happinessEnabled", "happiness.enabled");
        put(values, settings.passiveBreedingEnabled(),
                "passiveBreedingEnabled", "breeding.passiveBreedingEnabled", "breeding.passiveEnabled");
        put(values, settings.breedingRequiresHappiness(),
                "breedingRequiresHappiness", "breeding.requiresHappiness");
        put(values, settings.breedingGenderEnabled(),
                "breedingGenderEnabled", "breeding.genderEnabled");
        put(values, settings.traitsEnabled(),
                "traitsEnabled", "traits.enabled");
        put(values, settings.levelingEnabled(),
                "levelingEnabled", "progression.levelingEnabled");
        put(values, settings.talentsEnabled(),
                "talentsEnabled", "progression.talentsEnabled");
        put(values, settings.reviveSystemEnabled(),
                "reviveSystemEnabled", "revive.enabled");
        put(values, settings.recallTeleportingEnabled(),
                "recallTeleportingEnabled", "travel.recallTeleportingEnabled");
        put(values, settings.telemetryEnabled(),
                "telemetryEnabled", "telemetry.enabled");
        put(values, settings.telemetryBreadcrumbsEnabled(),
                "telemetryBreadcrumbsEnabled", "telemetry.breadcrumbsEnabled");
        return Map.copyOf(values);
    }

    private static void put(@Nonnull Map<String, JsonElement> values, boolean value, @Nonnull String... aliases) {
        put(values, new JsonPrimitive(value), aliases);
    }

    private static void put(@Nonnull Map<String, JsonElement> values, int value, @Nonnull String... aliases) {
        put(values, new JsonPrimitive(value), aliases);
    }

    private static void put(@Nonnull Map<String, JsonElement> values, double value, @Nonnull String... aliases) {
        put(values, new JsonPrimitive(value), aliases);
    }

    private static void put(@Nonnull Map<String, JsonElement> values,
                            @Nonnull String value,
                            @Nonnull String... aliases) {
        put(values, new JsonPrimitive(value), aliases);
    }

    private static void put(@Nonnull Map<String, JsonElement> values,
                            @Nonnull JsonElement value,
                            @Nonnull String... aliases) {
        for (String alias : aliases) {
            String key = normalizeSettingPath(alias);
            if (key != null) {
                values.put(key, value);
            }
        }
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
