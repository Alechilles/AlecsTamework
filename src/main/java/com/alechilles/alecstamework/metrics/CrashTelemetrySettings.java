package com.alechilles.alecstamework.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Store-first crash telemetry settings.
 *
 * <p>Endpoint and upload behavior are fixed. Telemetry is enabled by default and can be opted out.
 */
public final class CrashTelemetrySettings {

    public static final String FILE_NAME = "crash-telemetry.json";
    public static final String FIXED_ENDPOINT = "https://telemetry.alecsmods.com/ingest/event";

    static final boolean DEFAULT_ENABLED = true;
    static final boolean DEFAULT_BREADCRUMBS_ENABLED = true;
    static final int DEFAULT_BREADCRUMBS_CAPACITY = 40;
    static final int MIN_BREADCRUMBS_CAPACITY = 10;
    static final int MAX_BREADCRUMBS_CAPACITY = 200;
    static final int FIXED_CONNECT_TIMEOUT_MS = 2000;
    static final int FIXED_READ_TIMEOUT_MS = 3000;
    static final int FIXED_MAX_PENDING_REPORTS = 200;
    static final int FIXED_MAX_UPLOADS_PER_FLUSH = 10;

    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final Path filePath;
    private final boolean enabled;
    private final boolean breadcrumbsEnabled;
    private final int breadcrumbsCapacity;

    CrashTelemetrySettings(@Nonnull Path filePath,
                           boolean enabled,
                           boolean breadcrumbsEnabled,
                           int breadcrumbsCapacity) {
        this.filePath = filePath;
        this.enabled = enabled;
        this.breadcrumbsEnabled = breadcrumbsEnabled;
        this.breadcrumbsCapacity = breadcrumbsCapacity;
    }

    CrashTelemetrySettings(@Nonnull Path filePath, boolean enabled) {
        this(filePath, enabled, DEFAULT_BREADCRUMBS_ENABLED, DEFAULT_BREADCRUMBS_CAPACITY);
    }

    @Nonnull
    public static CrashTelemetrySettings load(@Nullable Path filePath, @Nullable HytaleLogger logger) {
        Path resolvedPath = filePath == null ? Path.of(FILE_NAME) : filePath;
        ensureTemplateExists(resolvedPath, logger);
        ParsedDocument parsed = readSettings(resolvedPath, logger);
        SettingsDocument values = parsed.document() == null ? new SettingsDocument() : parsed.document();

        boolean enabled = values.enabled == null ? DEFAULT_ENABLED : values.enabled;
        boolean breadcrumbsEnabled = values.breadcrumbsEnabled == null
                ? DEFAULT_BREADCRUMBS_ENABLED
                : values.breadcrumbsEnabled;
        int breadcrumbsCapacity = clampCapacity(values.breadcrumbsCapacity, logger);

        if (parsed.fromLegacyText()) {
            writeSettings(resolvedPath, enabled, breadcrumbsEnabled, breadcrumbsCapacity, logger);
        }

        return new CrashTelemetrySettings(
                resolvedPath,
                enabled,
                breadcrumbsEnabled,
                breadcrumbsCapacity
        );
    }

    public static boolean saveEnabled(@Nullable Path filePath, boolean enabled, @Nullable HytaleLogger logger) {
        Path resolvedPath = filePath == null ? Path.of(FILE_NAME) : filePath;
        CrashTelemetrySettings existing = load(resolvedPath, logger);
        return writeSettings(
                resolvedPath,
                enabled,
                existing.breadcrumbsEnabled(),
                existing.breadcrumbsCapacity(),
                logger
        );
    }

    public static boolean saveToggles(@Nullable Path filePath,
                                      boolean enabled,
                                      boolean breadcrumbsEnabled,
                                      @Nullable HytaleLogger logger) {
        Path resolvedPath = filePath == null ? Path.of(FILE_NAME) : filePath;
        CrashTelemetrySettings existing = load(resolvedPath, logger);
        return writeSettings(
                resolvedPath,
                enabled,
                breadcrumbsEnabled,
                existing.breadcrumbsCapacity(),
                logger
        );
    }

    @Nonnull
    public Path filePath() {
        return filePath;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean breadcrumbsEnabled() {
        return breadcrumbsEnabled;
    }

    public int breadcrumbsCapacity() {
        return breadcrumbsCapacity;
    }

    @Nonnull
    public String endpoint() {
        return FIXED_ENDPOINT;
    }

    public int connectTimeoutMs() {
        return FIXED_CONNECT_TIMEOUT_MS;
    }

    public int readTimeoutMs() {
        return FIXED_READ_TIMEOUT_MS;
    }

    public int maxPendingReports() {
        return FIXED_MAX_PENDING_REPORTS;
    }

    public int maxUploadsPerFlush() {
        return FIXED_MAX_UPLOADS_PER_FLUSH;
    }

    private static void ensureTemplateExists(@Nonnull Path filePath, @Nullable HytaleLogger logger) {
        if (Files.isRegularFile(filePath)) {
            return;
        }
        writeSettings(filePath, DEFAULT_ENABLED, DEFAULT_BREADCRUMBS_ENABLED, DEFAULT_BREADCRUMBS_CAPACITY, logger);
    }

    @Nonnull
    private static ParsedDocument readSettings(@Nonnull Path filePath, @Nullable HytaleLogger logger) {
        if (!Files.isRegularFile(filePath)) {
            return new ParsedDocument(new SettingsDocument(), false);
        }
        try {
            String raw = Files.readString(filePath, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return new ParsedDocument(new SettingsDocument(), false);
            }
            SettingsDocument parsed = GSON.fromJson(raw, SettingsDocument.class);
            if (parsed != null) {
                return new ParsedDocument(parsed, false);
            }
        } catch (JsonSyntaxException ignored) {
            SettingsDocument legacyParsed = parseLegacyKeyValue(filePath, logger);
            return new ParsedDocument(legacyParsed, true);
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(
                        "Unable to read crash telemetry settings file: " + filePath
                );
            }
            return new ParsedDocument(new SettingsDocument(), false);
        }
        return new ParsedDocument(new SettingsDocument(), false);
    }

    @Nonnull
    private static SettingsDocument parseLegacyKeyValue(@Nonnull Path filePath, @Nullable HytaleLogger logger) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(filePath, StandardCharsets.UTF_8)) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int equalsIndex = trimmed.indexOf('=');
                if (equalsIndex <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, equalsIndex).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(equalsIndex + 1).trim();
                if (key.isEmpty()) {
                    continue;
                }
                values.put(key, value);
            }
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(
                        "Unable to parse legacy crash telemetry settings file: " + filePath
                );
            }
        }

        SettingsDocument document = new SettingsDocument();
        document.enabled = parseBoolean(values.get("enabled"), DEFAULT_ENABLED);
        document.breadcrumbsEnabled = parseBoolean(values.get("breadcrumbs_enabled"), DEFAULT_BREADCRUMBS_ENABLED);
        document.breadcrumbsCapacity = parseInt(
                values.get("breadcrumbs_capacity"),
                DEFAULT_BREADCRUMBS_CAPACITY,
                MIN_BREADCRUMBS_CAPACITY,
                MAX_BREADCRUMBS_CAPACITY,
                "breadcrumbs_capacity",
                logger
        );
        return document;
    }

    private static boolean writeSettings(@Nonnull Path filePath,
                                         boolean enabled,
                                         boolean breadcrumbsEnabled,
                                         int breadcrumbsCapacity,
                                         @Nullable HytaleLogger logger) {
        SettingsDocument document = new SettingsDocument();
        document.version = CURRENT_VERSION;
        document.enabled = enabled;
        document.breadcrumbsEnabled = breadcrumbsEnabled;
        document.breadcrumbsCapacity = clampCapacity(breadcrumbsCapacity, logger);
        document.endpoint = FIXED_ENDPOINT;

        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String serialized = GSON.toJson(document) + System.lineSeparator();
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tmp, serialized, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(
                        "Unable to save crash telemetry settings file: " + filePath
                );
            }
            return false;
        }
    }

    private static int clampCapacity(@Nullable Integer value, @Nullable HytaleLogger logger) {
        int resolved = value == null ? DEFAULT_BREADCRUMBS_CAPACITY : value;
        return parseInt(String.valueOf(resolved),
                DEFAULT_BREADCRUMBS_CAPACITY,
                MIN_BREADCRUMBS_CAPACITY,
                MAX_BREADCRUMBS_CAPACITY,
                "breadcrumbs_capacity",
                logger);
    }

    private static boolean parseBoolean(@Nullable String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "on".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "off".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private static int parseInt(@Nullable String raw,
                                int defaultValue,
                                int min,
                                int max,
                                @Nonnull String key,
                                @Nullable HytaleLogger logger) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < min || parsed > max) {
                if (logger != null) {
                    logger.at(Level.WARNING).log(
                            "Ignoring out-of-range crash telemetry setting "
                                    + key
                                    + "="
                                    + parsed
                                    + " (expected "
                                    + min
                                    + ".."
                                    + max
                                    + ")."
                    );
                }
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).log(
                        "Ignoring non-numeric crash telemetry setting "
                                + key
                                + "="
                                + raw
                                + "."
                );
            }
            return defaultValue;
        }
    }

    private record ParsedDocument(@Nullable SettingsDocument document, boolean fromLegacyText) {
    }

    private static final class SettingsDocument {
        private Integer version;
        private Boolean enabled;
        private Boolean breadcrumbsEnabled;
        private Integer breadcrumbsCapacity;
        private String endpoint;
    }
}
