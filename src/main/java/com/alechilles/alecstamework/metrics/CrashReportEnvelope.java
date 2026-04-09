package com.alechilles.alecstamework.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serializable payload persisted to local crash telemetry storage.
 */
public record CrashReportEnvelope(int schemaVersion,
                                  @Nonnull String reportId,
                                  @Nonnull String source,
                                  @Nonnull String fingerprint,
                                  @Nonnull String capturedAtUtc,
                                  @Nonnull String lastCapturedAtUtc,
                                  int occurrenceCount,
                                  @Nonnull String pluginIdentifier,
                                  @Nonnull String pluginVersion,
                                  @Nonnull String threadName,
                                  @Nullable String worldName,
                                  @Nullable String worldRemovalReason,
                                  @Nullable String worldFailurePluginIdentifier,
                                  @Nonnull AttributionDetails attribution,
                                  @Nonnull ThrowableDetails throwable,
                                  @Nonnull List<Breadcrumb> breadcrumbs,
                                  @Nonnull RuntimeMetadata runtime) {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private static final Pattern MOD_VERSION_PATTERN = Pattern.compile(
            "^(.*?)(?:\\s+v|[-_ ]v?|[-_ ])(\\d+(?:\\.\\d+){1,3}(?:[-+][A-Za-z0-9._]+)?)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final int MAX_BREADCRUMBS_PER_REPORT = 200;
    private static final int MAX_LOADED_MODS = 200;

    public static final int SCHEMA_VERSION = 2;

    @Nonnull
    public static CrashReportEnvelope create(@Nonnull String source,
                                             @Nonnull String fingerprint,
                                             @Nonnull String pluginIdentifier,
                                             @Nonnull String pluginVersion,
                                             @Nonnull String threadName,
                                             @Nullable String worldName,
                                             @Nullable String worldRemovalReason,
                                             @Nullable String worldFailurePluginIdentifier,
                                             @Nonnull CrashAttribution.AttributionResult attributionResult,
                                             @Nonnull Throwable throwable) {
        return create(
                source,
                fingerprint,
                pluginIdentifier,
                pluginVersion,
                threadName,
                worldName,
                worldRemovalReason,
                worldFailurePluginIdentifier,
                attributionResult,
                throwable,
                RuntimeMetadata.capture(null),
                List.of()
        );
    }

    @Nonnull
    public static CrashReportEnvelope create(@Nonnull String source,
                                             @Nonnull String fingerprint,
                                             @Nonnull String pluginIdentifier,
                                             @Nonnull String pluginVersion,
                                             @Nonnull String threadName,
                                             @Nullable String worldName,
                                             @Nullable String worldRemovalReason,
                                             @Nullable String worldFailurePluginIdentifier,
                                             @Nonnull CrashAttribution.AttributionResult attributionResult,
                                             @Nonnull Throwable throwable,
                                             @Nonnull RuntimeMetadata runtimeMetadata,
                                             @Nonnull List<Breadcrumb> breadcrumbs) {
        String capturedAtUtc = Instant.now().toString();
        return new CrashReportEnvelope(
                SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                source,
                fingerprint,
                capturedAtUtc,
                capturedAtUtc,
                1,
                pluginIdentifier,
                pluginVersion,
                threadName,
                worldName,
                worldRemovalReason,
                worldFailurePluginIdentifier,
                new AttributionDetails(
                        attributionResult.identifiedPlugin(),
                        attributionResult.matchedPluginIdentifier(),
                        attributionResult.matchedStackPrefix()
                ),
                ThrowableDetails.from(throwable),
                sanitizeBreadcrumbs(breadcrumbs),
                runtimeMetadata.normalize()
        );
    }

    @Nonnull
    public static CrashReportEnvelope fromJson(@Nonnull String json) {
        CrashReportEnvelope parsed = GSON.fromJson(json, CrashReportEnvelope.class);
        return parsed == null ? nullReport() : parsed.normalize();
    }

    @Nonnull
    public String toJson() {
        return GSON.toJson(this) + System.lineSeparator();
    }

    @Nonnull
    public CrashReportEnvelope mergeDuplicateOccurrence(@Nonnull CrashReportEnvelope incoming) {
        CrashReportEnvelope current = normalize();
        CrashReportEnvelope latest = incoming.normalize();
        int mergedCount = safeOccurrenceAdd(current.occurrenceCount(), latest.occurrenceCount());
        List<Breadcrumb> mergedBreadcrumbs = mergeBreadcrumbs(current.breadcrumbs(), latest.breadcrumbs());
        return new CrashReportEnvelope(
                Math.max(current.schemaVersion(), latest.schemaVersion()),
                current.reportId(),
                chooseNonBlank(current.source(), latest.source()),
                current.fingerprint(),
                current.capturedAtUtc(),
                latest.lastCapturedAtUtc(),
                mergedCount,
                chooseNonBlank(current.pluginIdentifier(), latest.pluginIdentifier()),
                chooseNonBlank(current.pluginVersion(), latest.pluginVersion()),
                chooseNonBlank(current.threadName(), latest.threadName()),
                chooseNullable(current.worldName(), latest.worldName()),
                chooseNullable(current.worldRemovalReason(), latest.worldRemovalReason()),
                chooseNullable(current.worldFailurePluginIdentifier(), latest.worldFailurePluginIdentifier()),
                latest.attribution(),
                latest.throwable(),
                mergedBreadcrumbs,
                latest.runtime().normalize()
        );
    }

    @Nonnull
    private CrashReportEnvelope normalize() {
        String firstCapturedAt = normalizeTimestamp(capturedAtUtc(), Instant.now().toString());
        return new CrashReportEnvelope(
                schemaVersion() <= 0 ? SCHEMA_VERSION : schemaVersion(),
                normalizeNonBlank(reportId(), UUID.randomUUID().toString()),
                normalizeNonBlank(source(), "unknown"),
                normalizeNonBlank(fingerprint(), "unknown"),
                firstCapturedAt,
                normalizeTimestamp(lastCapturedAtUtc(), firstCapturedAt),
                normalizeOccurrence(occurrenceCount()),
                normalizeNonBlank(pluginIdentifier(), "unknown"),
                normalizeNonBlank(pluginVersion(), "unknown"),
                normalizeNonBlank(threadName(), "unknown"),
                normalizeNullable(worldName()),
                normalizeNullable(worldRemovalReason()),
                normalizeNullable(worldFailurePluginIdentifier()),
                attribution() == null ? new AttributionDetails(null, false, false) : attribution(),
                throwable() == null
                        ? new ThrowableDetails("java.lang.Throwable", "<empty>", List.of(), List.of())
                        : throwable(),
                sanitizeBreadcrumbs(breadcrumbs()),
                runtime() == null ? RuntimeMetadata.capture(null) : runtime().normalize()
        );
    }

    @Nonnull
    private static CrashReportEnvelope nullReport() {
        String now = Instant.now().toString();
        return new CrashReportEnvelope(
                SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                "unknown",
                "unknown",
                now,
                now,
                1,
                "unknown",
                "unknown",
                Thread.currentThread().getName(),
                null,
                null,
                null,
                new AttributionDetails(null, false, false),
                new ThrowableDetails("java.lang.Throwable", "<empty>", List.of(), List.of()),
                List.of(),
                RuntimeMetadata.capture(null)
        );
    }

    @Nonnull
    private static List<Breadcrumb> sanitizeBreadcrumbs(@Nullable List<Breadcrumb> breadcrumbs) {
        if (breadcrumbs == null || breadcrumbs.isEmpty()) {
            return List.of();
        }
        ArrayList<Breadcrumb> sanitized = new ArrayList<>(Math.min(MAX_BREADCRUMBS_PER_REPORT, breadcrumbs.size()));
        int startIndex = Math.max(0, breadcrumbs.size() - MAX_BREADCRUMBS_PER_REPORT);
        for (int i = startIndex; i < breadcrumbs.size(); i++) {
            Breadcrumb crumb = breadcrumbs.get(i);
            if (crumb == null) {
                continue;
            }
            String timestamp = normalizeTimestamp(crumb.timestampUtc(), Instant.now().toString());
            String category = normalizeNonBlank(crumb.category(), "event");
            String detail = normalizeNonBlank(crumb.detail(), "<empty>");
            sanitized.add(new Breadcrumb(timestamp, truncate(category, 60), truncate(detail, 240)));
        }
        return List.copyOf(sanitized);
    }

    @Nonnull
    private static List<Breadcrumb> mergeBreadcrumbs(@Nonnull List<Breadcrumb> first,
                                                     @Nonnull List<Breadcrumb> second) {
        if (first.isEmpty()) {
            return sanitizeBreadcrumbs(second);
        }
        if (second.isEmpty()) {
            return sanitizeBreadcrumbs(first);
        }
        ArrayList<Breadcrumb> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return sanitizeBreadcrumbs(merged);
    }

    private static int safeOccurrenceAdd(int left, int right) {
        long safeLeft = Math.max(1, left);
        long safeRight = Math.max(1, right);
        long sum = safeLeft + safeRight;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static int normalizeOccurrence(int value) {
        return value <= 0 ? 1 : value;
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Nonnull
    private static String normalizeTimestamp(@Nullable String value, @Nonnull String fallback) {
        String normalized = normalizeNullable(value);
        return normalized == null ? fallback : normalized;
    }

    @Nonnull
    private static String normalizeNonBlank(@Nullable String value, @Nonnull String fallback) {
        String normalized = normalizeNullable(value);
        return normalized == null ? fallback : normalized;
    }

    @Nullable
    private static String chooseNullable(@Nullable String existing, @Nullable String incoming) {
        return normalizeNullable(incoming) != null ? normalizeNullable(incoming) : normalizeNullable(existing);
    }

    @Nonnull
    private static String chooseNonBlank(@Nonnull String existing, @Nonnull String incoming) {
        String normalizedIncoming = normalizeNonBlank(incoming, "");
        if (!normalizedIncoming.isBlank()) {
            return normalizedIncoming;
        }
        return normalizeNonBlank(existing, "unknown");
    }

    @Nonnull
    private static String truncate(@Nonnull String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    public record AttributionDetails(@Nullable String identifiedPlugin,
                                     boolean matchedPluginIdentifier,
                                     boolean matchedStackPrefix) {
    }

    public record ThrowableDetails(@Nonnull String type,
                                   @Nonnull String message,
                                   @Nonnull List<String> stack,
                                   @Nonnull List<CauseDetails> causes) {

        private static final int MAX_STACK_FRAMES = 80;

        @Nonnull
        static ThrowableDetails from(@Nonnull Throwable throwable) {
            ArrayList<CauseDetails> causes = new ArrayList<>();
            Throwable cursor = throwable.getCause();
            int depth = 0;
            while (cursor != null && depth < 6) {
                causes.add(new CauseDetails(
                        cursor.getClass().getName(),
                        sanitizeMessage(cursor.getMessage()),
                        stackFrames(cursor.getStackTrace())
                ));
                cursor = cursor.getCause();
                depth++;
            }
            return new ThrowableDetails(
                    throwable.getClass().getName(),
                    sanitizeMessage(throwable.getMessage()),
                    stackFrames(throwable.getStackTrace()),
                    List.copyOf(causes)
            );
        }

        @Nonnull
        private static List<String> stackFrames(@Nonnull StackTraceElement[] stackTrace) {
            int frameCount = Math.min(stackTrace.length, MAX_STACK_FRAMES);
            ArrayList<String> frames = new ArrayList<>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                StackTraceElement frame = stackTrace[i];
                frames.add(frame.toString());
            }
            return List.copyOf(frames);
        }

        @Nonnull
        private static String sanitizeMessage(@Nullable String message) {
            if (message == null || message.isBlank()) {
                return "<empty>";
            }
            String normalized = message.trim();
            if (normalized.length() > 4000) {
                return normalized.substring(0, 4000);
            }
            return normalized;
        }
    }

    public record CauseDetails(@Nonnull String type,
                               @Nonnull String message,
                               @Nonnull List<String> stack) {
    }

    public record Breadcrumb(@Nonnull String timestampUtc,
                             @Nonnull String category,
                             @Nonnull String detail) {
    }

    public record RuntimeMetadata(@Nonnull String javaVersion,
                                  @Nonnull String runtimeVersion,
                                  @Nonnull String osName,
                                  @Nonnull String osVersion,
                                  @Nonnull String osArch,
                                  @Nonnull String hytaleBuild,
                                  @Nonnull String serverVersion,
                                  @Nonnull List<LoadedModMetadata> loadedMods) {
        @Nonnull
        static RuntimeMetadata capture(@Nullable JavaPlugin plugin) {
            Path modsDirectory = resolveModsDirectory(plugin);
            return new RuntimeMetadata(
                    systemProperty("java.version"),
                    Runtime.version().toString(),
                    systemProperty("os.name"),
                    systemProperty("os.version"),
                    systemProperty("os.arch"),
                    firstNonBlank(
                            systemProperty("hytale.build"),
                            systemProperty("hytale.build.id"),
                            systemProperty("hytale.build.version"),
                            "unknown"
                    ),
                    firstNonBlank(
                            systemProperty("hytale.server.version"),
                            systemProperty("hytale.version"),
                            "unknown"
                    ),
                    discoverLoadedMods(modsDirectory)
            );
        }

        @Nonnull
        RuntimeMetadata normalize() {
            return new RuntimeMetadata(
                    normalizeNonBlank(javaVersion(), "unknown"),
                    normalizeNonBlank(runtimeVersion(), "unknown"),
                    normalizeNonBlank(osName(), "unknown"),
                    normalizeNonBlank(osVersion(), "unknown"),
                    normalizeNonBlank(osArch(), "unknown"),
                    normalizeNonBlank(hytaleBuild(), "unknown"),
                    normalizeNonBlank(serverVersion(), "unknown"),
                    normalizeLoadedMods(loadedMods())
            );
        }

        @Nonnull
        private static List<LoadedModMetadata> discoverLoadedMods(@Nullable Path modsDirectory) {
            if (modsDirectory == null || !Files.isDirectory(modsDirectory)) {
                return List.of();
            }
            LinkedHashMap<String, String> discovered = new LinkedHashMap<>();
            try (var stream = Files.list(modsDirectory)) {
                List<Path> paths = stream
                        .filter(path -> {
                            String name = path.getFileName() == null ? "" : path.getFileName().toString();
                            if (name.startsWith(".")) {
                                return false;
                            }
                            if (Files.isDirectory(path)) {
                                return true;
                            }
                            String lower = name.toLowerCase(Locale.ROOT);
                            return lower.endsWith(".jar") || lower.endsWith(".zip");
                        })
                        .sorted()
                        .toList();
                for (Path path : paths) {
                    ModNameParts parts = parseModName(path.getFileName().toString());
                    discovered.putIfAbsent(parts.identifier(), parts.version());
                    if (discovered.size() >= MAX_LOADED_MODS) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                return List.of();
            }
            if (discovered.isEmpty()) {
                return List.of();
            }
            ArrayList<LoadedModMetadata> mods = new ArrayList<>(discovered.size());
            for (var entry : discovered.entrySet()) {
                mods.add(new LoadedModMetadata(
                        normalizeNonBlank(entry.getKey(), "unknown"),
                        normalizeNonBlank(entry.getValue(), "unknown")
                ));
            }
            return List.copyOf(mods);
        }

        @Nonnull
        private static ModNameParts parseModName(@Nonnull String rawName) {
            String base = rawName.trim();
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            Matcher matcher = MOD_VERSION_PATTERN.matcher(base);
            if (!matcher.matches()) {
                return new ModNameParts(base, "unknown");
            }
            String identifier = matcher.group(1) == null ? base : matcher.group(1).trim();
            String version = matcher.group(2) == null ? "unknown" : matcher.group(2).trim();
            return new ModNameParts(identifier.isBlank() ? base : identifier, version);
        }

        @Nullable
        private static Path resolveModsDirectory(@Nullable JavaPlugin plugin) {
            if (plugin == null) {
                return null;
            }
            try {
                Path dataDirectory = plugin.getDataDirectory();
                if (dataDirectory == null) {
                    return null;
                }
                Path parent = dataDirectory.toAbsolutePath().normalize().getParent();
                if (parent == null) {
                    return null;
                }
                String name = parent.getFileName() == null ? "" : parent.getFileName().toString();
                if (!"mods".equalsIgnoreCase(name)) {
                    return null;
                }
                return parent;
            } catch (Exception ignored) {
                return null;
            }
        }

        @Nonnull
        private static List<LoadedModMetadata> normalizeLoadedMods(@Nullable List<LoadedModMetadata> mods) {
            if (mods == null || mods.isEmpty()) {
                return List.of();
            }
            ArrayList<LoadedModMetadata> normalized = new ArrayList<>(Math.min(MAX_LOADED_MODS, mods.size()));
            for (LoadedModMetadata mod : mods) {
                if (mod == null) {
                    continue;
                }
                normalized.add(new LoadedModMetadata(
                        truncate(normalizeNonBlank(mod.identifier(), "unknown"), 200),
                        truncate(normalizeNonBlank(mod.version(), "unknown"), 80)
                ));
                if (normalized.size() >= MAX_LOADED_MODS) {
                    break;
                }
            }
            return List.copyOf(normalized);
        }

        @Nonnull
        private static String firstNonBlank(@Nonnull String first,
                                            @Nonnull String second,
                                            @Nonnull String fallback) {
            if (!first.isBlank()) {
                return first;
            }
            if (!second.isBlank()) {
                return second;
            }
            return fallback;
        }

        @Nonnull
        private static String firstNonBlank(@Nonnull String first,
                                            @Nonnull String second,
                                            @Nonnull String third,
                                            @Nonnull String fallback) {
            if (!first.isBlank()) {
                return first;
            }
            if (!second.isBlank()) {
                return second;
            }
            if (!third.isBlank()) {
                return third;
            }
            return fallback;
        }

        @Nonnull
        private static String systemProperty(@Nonnull String key) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                return "";
            }
            return value.trim();
        }
    }

    public record LoadedModMetadata(@Nonnull String identifier,
                                    @Nonnull String version) {
    }

    private record ModNameParts(@Nonnull String identifier, @Nonnull String version) {
    }
}
