package com.alechilles.alecstamework.persistence;

import com.alechilles.alecstamework.Tamework;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * JSON-backed curated universe settings used by /tw settings.
 */
public final class TameworkSettingsStore {
    public static final String SETTINGS_DIRECTORY_NAME = "Settings";
    public static final String GLOBAL_SETTINGS_FILE_NAME = "tamework-settings.json";

    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Object GLOBAL_CACHE_LOCK = new Object();
    private static final Object RUNTIME_OVERRIDES_CACHE_LOCK = new Object();
    private static final Object PATH_CACHE_LOCK = new Object();

    @Nullable
    private static volatile CachedGlobalDocument cachedGlobalDocument;
    @Nullable
    private static volatile CachedRuntimeOverrides cachedRuntimeOverrides;
    @Nullable
    private static volatile CachedResolvedPaths cachedResolvedPaths;

    private TameworkSettingsStore() {
    }

    @Nonnull
    public static Path resolveSettingsDirectory(@Nonnull Tamework plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return resolveCachedPaths(plugin).settingsDirectory();
    }

    @Nonnull
    public static Path resolveGlobalSettingsFile(@Nonnull Tamework plugin) {
        return resolveCachedPaths(plugin).globalSettingsFile();
    }

    @Nonnull
    public static Path resolveGlobalSettingsFile(@Nonnull Path tameworkUniverseRoot) {
        return tameworkUniverseRoot
                .resolve(SETTINGS_DIRECTORY_NAME)
                .resolve(GLOBAL_SETTINGS_FILE_NAME)
                .normalize();
    }

    @Nullable
    public static GlobalOverrides loadRuntimeGlobalOverrides() {
        final Tamework plugin;
        try {
            plugin = Tamework.getInstance();
        } catch (Throwable ignored) {
            return null;
        }
        if (plugin == null) {
            return null;
        }
        Path globalSettingsFile = resolveGlobalSettingsFile(plugin);
        CachedRuntimeOverrides cached = cachedRuntimeOverrides;
        if (cached != null && cached.path().equals(globalSettingsFile)) {
            return cached.overrides();
        }
        synchronized (RUNTIME_OVERRIDES_CACHE_LOCK) {
            cached = cachedRuntimeOverrides;
            if (cached != null && cached.path().equals(globalSettingsFile)) {
                return cached.overrides();
            }
            GlobalOverrides loaded = loadGlobalOverrides(globalSettingsFile, plugin.getLogger());
            cachedRuntimeOverrides = new CachedRuntimeOverrides(
                    globalSettingsFile,
                    loaded
            );
            return loaded;
        }
    }

    public static void invalidateRuntimeGlobalOverridesCache() {
        synchronized (RUNTIME_OVERRIDES_CACHE_LOCK) {
            cachedRuntimeOverrides = null;
        }
        synchronized (GLOBAL_CACHE_LOCK) {
            cachedGlobalDocument = null;
        }
        synchronized (PATH_CACHE_LOCK) {
            cachedResolvedPaths = null;
        }
    }

    @Nullable
    public static GlobalOverrides loadGlobalOverrides(@Nonnull Path globalSettingsFile, @Nullable HytaleLogger logger) {
        ensureGlobalTemplateExists(globalSettingsFile, logger);
        GlobalSettingsDocument document = loadGlobalDocument(globalSettingsFile, logger);
        if (document == null) {
            return null;
        }
        return toOverrides(document);
    }

    public static boolean saveGlobalSettings(@Nonnull Path globalSettingsFile,
                                             @Nonnull GlobalSettingsSnapshot snapshot,
                                             @Nullable HytaleLogger logger) {
        Objects.requireNonNull(globalSettingsFile, "globalSettingsFile");
        Objects.requireNonNull(snapshot, "snapshot");

        GlobalSettingsDocument document = new GlobalSettingsDocument();
        document.version = CURRENT_VERSION;

        document.population = new PopulationSection();
        document.population.limitPerPlayerOwnedTotal = Math.max(0, snapshot.populationLimitPerPlayerOwnedTotal());
        document.population.perPlayerLimitScope = normalizeScope(snapshot.populationPerPlayerLimitScope());

        document.simpleClaims = new SimpleClaimsSection();
        document.simpleClaims.simpleClaimsEnabled = snapshot.simpleClaimsEnabled();
        document.simpleClaims.limitPerClaimChunk = Math.max(0, snapshot.simpleClaimsLimitPerClaimChunk());
        document.simpleClaims.limitPerClaimTotal = Math.max(0, snapshot.simpleClaimsLimitPerClaimTotal());
        document.simpleClaims.breedingRequiresClaim = snapshot.simpleClaimsBreedingRequiresClaim();
        document.simpleClaims.protectTamedFromNonMembers = snapshot.simpleClaimsProtectTamedFromNonMembers();

        document.ownership = new OwnershipSection();
        document.ownership.damageProtection = new OwnershipDamageProtectionSection();
        document.ownership.damageProtection.blockOwnerDamage = snapshot.blockOwnerDamage();
        document.ownership.damageProtection.blockAllPlayerDamageIfOwned = snapshot.blockAllPlayerDamageIfOwned();
        document.ownership.damageProtection.invulnerableIfOwned = snapshot.invulnerableIfOwned();
        document.ownership.capture = new OwnershipCaptureSection();
        document.ownership.capture.captureRequiresOwner = snapshot.captureRequiresOwner();
        document.ownership.capture.spawnRequiresOwner = snapshot.spawnRequiresOwner();
        document.ownership.capture.captureClearsOwner = snapshot.captureClearsOwner();
        document.ownership.capture.spawnSetsOwner = snapshot.spawnSetsOwner();
        document.ownership.interactionRequiresOwner = snapshot.interactionRequiresOwner();
        document.ownership.linkingRequiresOwner = snapshot.linkingRequiresOwner();

        document.needs = new NeedsSection();
        document.needs.enabled = snapshot.needsEnabled();
        document.needs.tickPolicy = new NeedsTickPolicySection();
        document.needs.tickPolicy.mode = trimToNull(snapshot.needsTickPolicyMode());
        document.needs.tickPolicy.ownerOfflineGraceHours = Math.max(0.0, snapshot.needsOwnerOfflineGraceHours());
        document.needs.tickPolicy.ownerOfflineDecayMultiplier = Math.max(0.0, snapshot.needsOwnerOfflineDecayMultiplier());
        document.needs.damage = new NeedsDamageSection();
        document.needs.damage.enabled = snapshot.needsDamageEnabled();
        document.needs.damage.model = trimToNull(snapshot.needsDamageModel());
        document.needs.damage.dualNeedRule = trimToNull(snapshot.needsDamageDualNeedRule());
        document.needs.damage.starvationDamagePerMinute = Math.max(0.0, snapshot.needsStarvationDamagePerMinute());
        document.needs.damage.dehydrationDamagePerMinute = Math.max(0.0, snapshot.needsDehydrationDamagePerMinute());
        document.needs.damage.lethal = snapshot.needsDamageLethal();

        document.happiness = new HappinessSection();
        document.happiness.enabled = snapshot.happinessEnabled();

        document.breeding = new BreedingSection();
        document.breeding.passiveBreedingEnabled = snapshot.passiveBreedingEnabled();
        document.breeding.requiresHappiness = snapshot.breedingRequiresHappiness();
        document.breeding.genderEnabled = snapshot.breedingGenderEnabled();

        document.traits = new TraitsSection();
        document.traits.enabled = snapshot.traitsEnabled();

        document.revive = new ReviveSection();
        document.revive.enabled = snapshot.reviveSystemEnabled();

        document.travel = new TravelSection();
        document.travel.recallTeleportingEnabled = snapshot.recallTeleportingEnabled();

        document.telemetry = new TelemetrySection();
        document.telemetry.enabled = snapshot.telemetryEnabled();
        document.telemetry.breadcrumbsEnabled = snapshot.telemetryBreadcrumbsEnabled();

        if (!writeDocument(globalSettingsFile, document, logger)) {
            return false;
        }
        updateGlobalCache(globalSettingsFile, document);
        updateRuntimeOverridesCache(globalSettingsFile, toOverrides(document));
        return true;
    }

    public static boolean importLegacyTelemetrySettingsIfMissing(@Nonnull Path globalSettingsFile,
                                                                 @Nonnull java.util.List<Path> legacySettingsCandidates,
                                                                 @Nullable HytaleLogger logger) {
        Objects.requireNonNull(globalSettingsFile, "globalSettingsFile");
        Objects.requireNonNull(legacySettingsCandidates, "legacySettingsCandidates");
        GlobalSettingsDocument existing = readDocument(globalSettingsFile, logger);
        if (hasTelemetrySettings(existing)) {
            return true;
        }
        TelemetrySection imported = null;
        Path importedFrom = null;
        for (Path candidate : legacySettingsCandidates) {
            Path source = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                continue;
            }
            imported = readLegacyTelemetrySettings(source, logger);
            if (imported != null) {
                importedFrom = source;
                break;
            }
        }
        if (imported == null) {
            return true;
        }
        GlobalSettingsDocument document = existing == null ? createDefaultGlobalSettingsDocument() : existing;
        document.telemetry = imported;
        if (!writeDocument(globalSettingsFile, document, logger)) {
            return false;
        }
        updateGlobalCache(globalSettingsFile, document);
        updateRuntimeOverridesCache(globalSettingsFile, toOverrides(document));
        if (logger != null && importedFrom != null) {
            logger.at(Level.INFO).log("Imported legacy Tamework telemetry settings from " + importedFrom + ".");
        }
        return true;
    }

    @Nonnull
    public static Path resolveTameworkUniverseRoot(@Nonnull Tamework plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return resolveCachedPaths(plugin).tameworkUniverseRoot();
    }

    @Nonnull
    private static CachedResolvedPaths resolveCachedPaths(@Nonnull Tamework plugin) {
        CachedResolvedPaths cached = cachedResolvedPaths;
        if (cached != null && cached.plugin() == plugin) {
            return cached;
        }
        synchronized (PATH_CACHE_LOCK) {
            cached = cachedResolvedPaths;
            if (cached != null && cached.plugin() == plugin) {
                return cached;
            }
            CachedResolvedPaths resolved = buildResolvedPaths(plugin);
            cachedResolvedPaths = resolved;
            return resolved;
        }
    }

    @Nonnull
    private static CachedResolvedPaths buildResolvedPaths(@Nonnull Tamework plugin) {
        Path runtimeDataDirectory = plugin.getRuntimeDataDirectory();
        Path resolvedDataDirectory = runtimeDataDirectory;
        if (resolvedDataDirectory == null) {
            resolvedDataDirectory = new TameworkDataPathService(plugin.getLogger())
                    .resolveAndMigrateDataDirectory(plugin.getDataDirectory());
        }
        Path normalized = resolvedDataDirectory.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path tameworkUniverseRoot = parent == null ? normalized : parent;
        Path settingsDirectory = tameworkUniverseRoot.resolve(SETTINGS_DIRECTORY_NAME).normalize();
        Path globalSettingsFile = settingsDirectory.resolve(GLOBAL_SETTINGS_FILE_NAME).normalize();
        return new CachedResolvedPaths(plugin, tameworkUniverseRoot, settingsDirectory, globalSettingsFile);
    }

    @Nullable
    private static GlobalSettingsDocument loadGlobalDocument(@Nonnull Path globalSettingsFile,
                                                             @Nullable HytaleLogger logger) {
        long modifiedMillis = lastModifiedMillis(globalSettingsFile);
        CachedGlobalDocument cached = cachedGlobalDocument;
        if (cached != null
                && cached.path().equals(globalSettingsFile)
                && cached.lastModifiedMillis() == modifiedMillis) {
            return cached.document();
        }

        synchronized (GLOBAL_CACHE_LOCK) {
            cached = cachedGlobalDocument;
            if (cached != null
                    && cached.path().equals(globalSettingsFile)
                    && cached.lastModifiedMillis() == modifiedMillis) {
                return cached.document();
            }
            GlobalSettingsDocument loaded = readDocument(globalSettingsFile, logger);
            cachedGlobalDocument = new CachedGlobalDocument(globalSettingsFile, modifiedMillis, loaded);
            return loaded;
        }
    }

    private static void updateGlobalCache(@Nonnull Path globalSettingsFile,
                                          @Nonnull GlobalSettingsDocument document) {
        synchronized (GLOBAL_CACHE_LOCK) {
            cachedGlobalDocument = new CachedGlobalDocument(
                    globalSettingsFile,
                    lastModifiedMillis(globalSettingsFile),
                    document
            );
        }
    }

    private static void updateRuntimeOverridesCache(@Nonnull Path globalSettingsFile,
                                                    @Nullable GlobalOverrides overrides) {
        synchronized (RUNTIME_OVERRIDES_CACHE_LOCK) {
            cachedRuntimeOverrides = new CachedRuntimeOverrides(
                    globalSettingsFile,
                    overrides
            );
        }
    }

    private static void ensureGlobalTemplateExists(@Nonnull Path globalSettingsFile, @Nullable HytaleLogger logger) {
        if (Files.isRegularFile(globalSettingsFile)) {
            return;
        }
        writeDocument(globalSettingsFile, createDefaultGlobalSettingsDocument(), logger);
    }

    @Nonnull
    private static GlobalSettingsDocument createDefaultGlobalSettingsDocument() {
        GlobalSettingsDocument document = new GlobalSettingsDocument();
        document.version = CURRENT_VERSION;

        document.population = new PopulationSection();
        document.population.limitPerPlayerOwnedTotal = 0;
        document.population.perPlayerLimitScope = "PerWorld";

        document.simpleClaims = new SimpleClaimsSection();
        document.simpleClaims.simpleClaimsEnabled = false;
        document.simpleClaims.limitPerClaimChunk = 0;
        document.simpleClaims.limitPerClaimTotal = 0;
        document.simpleClaims.breedingRequiresClaim = false;
        document.simpleClaims.protectTamedFromNonMembers = false;

        document.ownership = new OwnershipSection();
        document.ownership.damageProtection = new OwnershipDamageProtectionSection();
        document.ownership.damageProtection.blockOwnerDamage = false;
        document.ownership.damageProtection.blockAllPlayerDamageIfOwned = false;
        document.ownership.damageProtection.invulnerableIfOwned = false;
        document.ownership.capture = new OwnershipCaptureSection();
        document.ownership.capture.captureRequiresOwner = true;
        document.ownership.capture.spawnRequiresOwner = true;
        document.ownership.capture.captureClearsOwner = true;
        document.ownership.capture.spawnSetsOwner = true;
        document.ownership.interactionRequiresOwner = true;
        document.ownership.linkingRequiresOwner = true;

        document.needs = new NeedsSection();
        document.needs.enabled = true;
        document.needs.tickPolicy = new NeedsTickPolicySection();
        document.needs.tickPolicy.mode = "OWNER_ONLINE_GRACE_THEN_DECAY";
        document.needs.tickPolicy.ownerOfflineGraceHours = 72.0;
        document.needs.tickPolicy.ownerOfflineDecayMultiplier = 1.0;
        document.needs.damage = new NeedsDamageSection();
        document.needs.damage.enabled = true;
        document.needs.damage.model = "MIN_ONLY_PERCENT";
        document.needs.damage.dualNeedRule = "USE_HIGHER_ONLY";
        document.needs.damage.starvationDamagePerMinute = 2.0;
        document.needs.damage.dehydrationDamagePerMinute = 3.0;
        document.needs.damage.lethal = true;

        document.happiness = new HappinessSection();
        document.happiness.enabled = true;

        document.breeding = new BreedingSection();
        document.breeding.passiveBreedingEnabled = true;
        document.breeding.requiresHappiness = true;
        document.breeding.genderEnabled = true;

        document.traits = new TraitsSection();
        document.traits.enabled = true;

        document.revive = new ReviveSection();
        document.revive.enabled = true;

        document.travel = new TravelSection();
        document.travel.recallTeleportingEnabled = true;

        document.telemetry = new TelemetrySection();
        document.telemetry.enabled = true;
        document.telemetry.breadcrumbsEnabled = true;
        return document;
    }

    @Nullable
    private static GlobalSettingsDocument readDocument(@Nonnull Path globalSettingsFile, @Nullable HytaleLogger logger) {
        if (!Files.isRegularFile(globalSettingsFile)) {
            return null;
        }
        try {
            String raw = Files.readString(globalSettingsFile, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            GlobalSettingsDocument parsed = GSON.fromJson(raw, GlobalSettingsDocument.class);
            if (parsed == null) {
                return null;
            }
            return parsed;
        } catch (JsonSyntaxException syntaxException) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(syntaxException).log(
                        "Invalid Tamework settings JSON; ignoring file " + globalSettingsFile + "."
                );
            }
            return null;
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(
                        "Unable to read Tamework settings file " + globalSettingsFile + "."
                );
            }
            return null;
        }
    }

    private static boolean writeDocument(@Nonnull Path globalSettingsFile,
                                         @Nonnull GlobalSettingsDocument document,
                                         @Nullable HytaleLogger logger) {
        try {
            Path parent = globalSettingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String serialized = GSON.toJson(document) + System.lineSeparator();
            Path tmp = globalSettingsFile.resolveSibling(globalSettingsFile.getFileName() + ".tmp");
            Files.writeString(tmp, serialized, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, globalSettingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                Files.move(tmp, globalSettingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(
                        "Unable to save Tamework settings file " + globalSettingsFile + "."
                );
            }
            return false;
        }
    }

    private static long lastModifiedMillis(@Nonnull Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return -1L;
            }
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    @Nonnull
    private static String normalizeScope(@Nullable String scope) {
        if (scope == null || scope.isBlank()) {
            return "PerWorld";
        }
        String normalized = scope.trim();
        if ("global".equalsIgnoreCase(normalized)) {
            return "Global";
        }
        return "PerWorld";
    }

    @Nonnull
    private static GlobalOverrides toOverrides(@Nonnull GlobalSettingsDocument document) {
        PopulationSection population = document.population;
        SimpleClaimsSection simpleClaims = document.simpleClaims;
        OwnershipSection ownership = document.ownership;
        OwnershipDamageProtectionSection ownershipDamageProtection =
                ownership != null ? ownership.damageProtection : null;
        OwnershipCaptureSection ownershipCapture = ownership != null ? ownership.capture : null;
        NeedsSection needs = document.needs;
        NeedsTickPolicySection needsTickPolicy = needs != null ? needs.tickPolicy : null;
        NeedsDamageSection needsDamage = needs != null ? needs.damage : null;
        HappinessSection happiness = document.happiness;
        BreedingSection breeding = document.breeding;
        TraitsSection traits = document.traits;
        ReviveSection revive = document.revive;
        TravelSection travel = document.travel;
        TelemetrySection telemetry = document.telemetry;

        return new GlobalOverrides(
                population != null ? population.limitPerPlayerOwnedTotal : null,
                population != null ? trimToNull(population.perPlayerLimitScope) : null,
                simpleClaims != null ? simpleClaims.simpleClaimsEnabled : null,
                simpleClaims != null ? simpleClaims.limitPerClaimChunk : null,
                simpleClaims != null ? simpleClaims.limitPerClaimTotal : null,
                simpleClaims != null ? simpleClaims.breedingRequiresClaim : null,
                simpleClaims != null ? simpleClaims.protectTamedFromNonMembers : null,
                ownershipDamageProtection != null
                        ? ownershipDamageProtection.blockOwnerDamage
                        : null,
                ownershipDamageProtection != null
                        ? ownershipDamageProtection.blockAllPlayerDamageIfOwned
                        : null,
                ownershipDamageProtection != null
                        ? ownershipDamageProtection.invulnerableIfOwned
                        : null,
                ownershipCapture != null
                        ? ownershipCapture.captureClearsOwner
                        : null,
                ownershipCapture != null
                        ? ownershipCapture.spawnSetsOwner
                        : null,
                ownershipCapture != null
                        ? ownershipCapture.captureRequiresOwner
                        : null,
                ownershipCapture != null
                        ? ownershipCapture.spawnRequiresOwner
                        : null,
                ownership != null
                        ? ownership.interactionRequiresOwner
                        : null,
                ownership != null
                        ? ownership.linkingRequiresOwner
                        : null,
                needs != null ? needs.enabled : null,
                needsTickPolicy != null ? trimToNull(needsTickPolicy.mode) : null,
                needsTickPolicy != null ? needsTickPolicy.ownerOfflineGraceHours : null,
                needsTickPolicy != null ? needsTickPolicy.ownerOfflineDecayMultiplier : null,
                needsDamage != null ? needsDamage.enabled : null,
                needsDamage != null ? trimToNull(needsDamage.model) : null,
                needsDamage != null ? trimToNull(needsDamage.dualNeedRule) : null,
                needsDamage != null ? needsDamage.starvationDamagePerMinute : null,
                needsDamage != null ? needsDamage.dehydrationDamagePerMinute : null,
                needsDamage != null ? needsDamage.lethal : null,
                happiness != null ? happiness.enabled : null,
                breeding != null ? breeding.passiveBreedingEnabled : null,
                breeding != null ? breeding.requiresHappiness : null,
                breeding != null ? breeding.genderEnabled : null,
                traits != null ? traits.enabled : null,
                revive != null ? revive.enabled : null,
                travel != null ? travel.recallTeleportingEnabled : null,
                telemetry != null ? telemetry.enabled : null,
                telemetry != null ? telemetry.breadcrumbsEnabled : null
        );
    }

    private static boolean hasTelemetrySettings(@Nullable GlobalSettingsDocument document) {
        return document != null
                && document.telemetry != null
                && (document.telemetry.enabled != null || document.telemetry.breadcrumbsEnabled != null);
    }

    @Nullable
    private static TelemetrySection readLegacyTelemetrySettings(@Nonnull Path file, @Nullable HytaleLogger logger) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return null;
            }
            TelemetrySection section = raw.trim().startsWith("{")
                    ? parseLegacyTelemetryJson(raw)
                    : parseLegacyTelemetryText(raw);
            return hasTelemetryValues(section) ? section : null;
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log("Unable to read legacy Tamework telemetry settings file " + file + ".");
            }
            return null;
        }
    }

    @Nullable
    private static TelemetrySection parseLegacyTelemetryJson(@Nonnull String raw) {
        JsonElement parsed = JsonParser.parseString(raw);
        if (parsed == null || !parsed.isJsonObject()) {
            return null;
        }
        JsonObject object = parsed.getAsJsonObject();
        TelemetrySection section = new TelemetrySection();
        section.enabled = optionalBoolean(object, "enabled");
        section.breadcrumbsEnabled = firstBoolean(object, "breadcrumbsEnabled", "breadcrumbs_enabled");
        return section;
    }

    @Nonnull
    private static TelemetrySection parseLegacyTelemetryText(@Nonnull String raw) {
        TelemetrySection section = new TelemetrySection();
        for (String line : raw.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            Boolean value = parseBoolean(line.substring(separator + 1));
            if (value == null) {
                continue;
            }
            if ("enabled".equalsIgnoreCase(key)) {
                section.enabled = value;
            } else if ("breadcrumbs_enabled".equalsIgnoreCase(key) || "breadcrumbsEnabled".equalsIgnoreCase(key)) {
                section.breadcrumbsEnabled = value;
            }
        }
        return section;
    }

    @Nullable
    private static Boolean firstBoolean(@Nonnull JsonObject object, @Nonnull String... keys) {
        for (String key : keys) {
            Boolean value = optionalBoolean(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static Boolean optionalBoolean(@Nonnull JsonObject object, @Nonnull String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static Boolean parseBoolean(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        if ("1".equals(normalized) || "on".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "off".equalsIgnoreCase(normalized) || "no".equalsIgnoreCase(normalized)) {
            return false;
        }
        return null;
    }

    private static boolean hasTelemetryValues(@Nullable TelemetrySection section) {
        return section != null && (section.enabled != null || section.breadcrumbsEnabled != null);
    }

    /**
     * Fully-specified curated /tw settings snapshot used for persistence.
     */
    public record GlobalSettingsSnapshot(int populationLimitPerPlayerOwnedTotal,
                                         @Nonnull String populationPerPlayerLimitScope,
                                         boolean simpleClaimsEnabled,
                                         int simpleClaimsLimitPerClaimChunk,
                                         int simpleClaimsLimitPerClaimTotal,
                                         boolean simpleClaimsBreedingRequiresClaim,
                                         boolean simpleClaimsProtectTamedFromNonMembers,
                                         boolean blockOwnerDamage,
                                         boolean blockAllPlayerDamageIfOwned,
                                         boolean invulnerableIfOwned,
                                         boolean captureClearsOwner,
                                         boolean spawnSetsOwner,
                                          boolean captureRequiresOwner,
                                          boolean spawnRequiresOwner,
                                          boolean interactionRequiresOwner,
                                          boolean linkingRequiresOwner,
                                          boolean needsEnabled,
                                          @Nonnull String needsTickPolicyMode,
                                          double needsOwnerOfflineGraceHours,
                                          double needsOwnerOfflineDecayMultiplier,
                                          boolean needsDamageEnabled,
                                          @Nonnull String needsDamageModel,
                                          @Nonnull String needsDamageDualNeedRule,
                                          double needsStarvationDamagePerMinute,
                                          double needsDehydrationDamagePerMinute,
                                          boolean needsDamageLethal,
                                          boolean happinessEnabled,
                                          boolean passiveBreedingEnabled,
                                          boolean breedingRequiresHappiness,
                                          boolean breedingGenderEnabled,
                                          boolean traitsEnabled,
                                          boolean reviveSystemEnabled,
                                          boolean recallTeleportingEnabled,
                                          boolean telemetryEnabled,
                                          boolean telemetryBreadcrumbsEnabled) {
    }

    /**
     * Optional override values loaded from the JSON settings document.
     */
    public record GlobalOverrides(@Nullable Integer populationLimitPerPlayerOwnedTotal,
                                  @Nullable String populationPerPlayerLimitScope,
                                  @Nullable Boolean simpleClaimsEnabled,
                                  @Nullable Integer simpleClaimsLimitPerClaimChunk,
                                  @Nullable Integer simpleClaimsLimitPerClaimTotal,
                                  @Nullable Boolean simpleClaimsBreedingRequiresClaim,
                                  @Nullable Boolean simpleClaimsProtectTamedFromNonMembers,
                                  @Nullable Boolean blockOwnerDamage,
                                  @Nullable Boolean blockAllPlayerDamageIfOwned,
                                  @Nullable Boolean invulnerableIfOwned,
                                  @Nullable Boolean captureClearsOwner,
                                  @Nullable Boolean spawnSetsOwner,
                                   @Nullable Boolean captureRequiresOwner,
                                   @Nullable Boolean spawnRequiresOwner,
                                   @Nullable Boolean interactionRequiresOwner,
                                   @Nullable Boolean linkingRequiresOwner,
                                   @Nullable Boolean needsEnabled,
                                   @Nullable String needsTickPolicyMode,
                                   @Nullable Double needsOwnerOfflineGraceHours,
                                   @Nullable Double needsOwnerOfflineDecayMultiplier,
                                   @Nullable Boolean needsDamageEnabled,
                                   @Nullable String needsDamageModel,
                                   @Nullable String needsDamageDualNeedRule,
                                   @Nullable Double needsStarvationDamagePerMinute,
                                   @Nullable Double needsDehydrationDamagePerMinute,
                                   @Nullable Boolean needsDamageLethal,
                                   @Nullable Boolean happinessEnabled,
                                   @Nullable Boolean passiveBreedingEnabled,
                                   @Nullable Boolean breedingRequiresHappiness,
                                   @Nullable Boolean breedingGenderEnabled,
                                   @Nullable Boolean traitsEnabled,
                                   @Nullable Boolean reviveSystemEnabled,
                                   @Nullable Boolean recallTeleportingEnabled,
                                   @Nullable Boolean telemetryEnabled,
                                   @Nullable Boolean telemetryBreadcrumbsEnabled) {
    }

    private record CachedGlobalDocument(@Nonnull Path path,
                                        long lastModifiedMillis,
                                        @Nullable GlobalSettingsDocument document) {
    }

    private record CachedRuntimeOverrides(@Nonnull Path path,
                                          @Nullable GlobalOverrides overrides) {
    }

    private record CachedResolvedPaths(@Nonnull Tamework plugin,
                                       @Nonnull Path tameworkUniverseRoot,
                                       @Nonnull Path settingsDirectory,
                                       @Nonnull Path globalSettingsFile) {
    }

    private static final class GlobalSettingsDocument {
        private Integer version;
        private PopulationSection population;
        private SimpleClaimsSection simpleClaims;
        private OwnershipSection ownership;
        private NeedsSection needs;
        private HappinessSection happiness;
        private BreedingSection breeding;
        private TraitsSection traits;
        private ReviveSection revive;
        private TravelSection travel;
        private TelemetrySection telemetry;
    }

    private static final class PopulationSection {
        private Integer limitPerPlayerOwnedTotal;
        private String perPlayerLimitScope;
    }

    private static final class SimpleClaimsSection {
        private Boolean simpleClaimsEnabled;
        private Integer limitPerClaimChunk;
        private Integer limitPerClaimTotal;
        private Boolean breedingRequiresClaim;
        private Boolean protectTamedFromNonMembers;
    }

    private static final class OwnershipSection {
        private OwnershipDamageProtectionSection damageProtection;
        private OwnershipCaptureSection capture;
        private Boolean interactionRequiresOwner;
        private Boolean linkingRequiresOwner;
    }

    private static final class OwnershipDamageProtectionSection {
        private Boolean blockOwnerDamage;
        private Boolean blockAllPlayerDamageIfOwned;
        private Boolean invulnerableIfOwned;
    }

    private static final class OwnershipCaptureSection {
        private Boolean captureRequiresOwner;
        private Boolean spawnRequiresOwner;
        private Boolean captureClearsOwner;
        @SerializedName(value = "SpawnSetsOwner", alternate = {"spawnSetsOwner"})
        private Boolean spawnSetsOwner;
    }

    private static final class NeedsSection {
        private Boolean enabled;
        private NeedsTickPolicySection tickPolicy;
        private NeedsDamageSection damage;
    }

    private static final class HappinessSection {
        private Boolean enabled;
    }

    private static final class BreedingSection {
        private Boolean passiveBreedingEnabled;
        private Boolean requiresHappiness;
        private Boolean genderEnabled;
    }

    private static final class TraitsSection {
        private Boolean enabled;
    }

    private static final class NeedsTickPolicySection {
        private String mode;
        private Double ownerOfflineGraceHours;
        private Double ownerOfflineDecayMultiplier;
    }

    private static final class NeedsDamageSection {
        private Boolean enabled;
        private String model;
        private String dualNeedRule;
        private Double starvationDamagePerMinute;
        private Double dehydrationDamagePerMinute;
        private Boolean lethal;
    }

    private static final class ReviveSection {
        private Boolean enabled;
    }

    private static final class TravelSection {
        private Boolean recallTeleportingEnabled;
    }

    private static final class TelemetrySection {
        private Boolean enabled;
        private Boolean breadcrumbsEnabled;
    }
}
