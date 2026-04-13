package com.alechilles.alecstamework.persistence;

import com.alechilles.alecstamework.Tamework;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stores the login-time settings announcement config plus per-player opt-out state.
 */
public final class TameworkSettingsAnnouncementStore {
    public static final String ANNOUNCEMENT_FILE_NAME = "tamework-settings-announcement.json";
    public static final String ANNOUNCEMENT_STATE_FILE_NAME = "tamework-settings-announcement-state.json";
    public static final String BUILT_IN_ANNOUNCEMENT_ID = "settings-review-v2";
    public static final String BUILT_IN_TITLE_KEY = "tamework.ui.settingsAnnouncement.title";
    public static final String BUILT_IN_SUBTITLE_KEY = "tamework.ui.settingsAnnouncement.subtitle";
    public static final String BUILT_IN_OPT_OUT_LABEL_KEY = "tamework.ui.settingsAnnouncement.optOut";
    public static final String[] BUILT_IN_BODY_LINE_KEYS = {
            "tamework.ui.settingsAnnouncement.body.intro",
            "tamework.ui.settingsAnnouncement.body.defaults",
            "tamework.ui.settingsAnnouncement.body.scope"
    };

    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Object STATE_LOCK = new Object();
    private static final String DEFAULT_TITLE = "Important Alec's Tamework Update";
    private static final String DEFAULT_SUBTITLE =
            "New features, settings, or updates have been added to Tamework that require your attention.";
    private static final String DEFAULT_OPT_OUT_LABEL = "Don't show again until next announcement";
    private static final String[] DEFAULT_BODY_LINES = {
            "As of Alec's Tamework v2.8.0+, many important settings were migrated to the \"/tw settings\" menu. If you have not checked these settings, please click \"Review Settings\" to ensure everything is set up the way you would expect for your server.",
            "This includes things like \"Hunger/Thirst damage\" being enabled by default, and \"per-player/per-claim taming/breeding limits\" that may be vitally important to server owners.",
            "These popups are only shown to OPs and players with the permissions to use /tw settings, and will only appear after major changes that have been deemed important enough to require your immediate attention."
    };

    private TameworkSettingsAnnouncementStore() {
    }

    @Nonnull
    public static Path resolveAnnouncementConfigFile(@Nonnull Tamework plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return TameworkSettingsStore.resolveSettingsDirectory(plugin).resolve(ANNOUNCEMENT_FILE_NAME).normalize();
    }

    @Nonnull
    public static Path resolveAnnouncementConfigFile(@Nonnull Path tameworkRoot) {
        Objects.requireNonNull(tameworkRoot, "tameworkRoot");
        return tameworkRoot.resolve(TameworkSettingsStore.SETTINGS_DIRECTORY_NAME)
                .resolve(ANNOUNCEMENT_FILE_NAME)
                .normalize();
    }

    @Nonnull
    public static Path resolveAnnouncementStateFile(@Nonnull Tamework plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return TameworkSettingsStore.resolveSettingsDirectory(plugin).resolve(ANNOUNCEMENT_STATE_FILE_NAME).normalize();
    }

    @Nonnull
    public static Path resolveAnnouncementStateFile(@Nonnull Path tameworkRoot) {
        Objects.requireNonNull(tameworkRoot, "tameworkRoot");
        return tameworkRoot.resolve(TameworkSettingsStore.SETTINGS_DIRECTORY_NAME)
                .resolve(ANNOUNCEMENT_STATE_FILE_NAME)
                .normalize();
    }

    @Nonnull
    public static ResolvedAnnouncement loadResolvedAnnouncement(@Nonnull Path announcementFile,
                                                                @Nullable HytaleLogger logger) {
        Objects.requireNonNull(announcementFile, "announcementFile");
        ensureAnnouncementTemplateExists(announcementFile, logger);
        AnnouncementDocument document = readAnnouncementDocument(announcementFile, logger);
        if (document == null) {
            document = createDefaultAnnouncementDocument();
        }
        boolean enabled = document.enabled == null || document.enabled;
        boolean useBuiltInAnnouncementId = document.useBuiltInAnnouncementId == null || document.useBuiltInAnnouncementId;
        boolean useBuiltInText = resolveUseBuiltInText(document);
        String announcementId = useBuiltInAnnouncementId
                ? BUILT_IN_ANNOUNCEMENT_ID
                : firstNonBlank(document.announcementId, BUILT_IN_ANNOUNCEMENT_ID);
        String title = firstNonBlank(document.title, DEFAULT_TITLE);
        List<String> bodyLines = sanitizeLines(document.bodyLines, DEFAULT_BODY_LINES);
        String subtitle = firstNonBlank(document.subtitle, DEFAULT_SUBTITLE);
        String optOutLabel = firstNonBlank(document.optOutLabel, DEFAULT_OPT_OUT_LABEL);
        return new ResolvedAnnouncement(
                enabled,
                announcementId,
                useBuiltInText,
                title,
                subtitle,
                List.copyOf(bodyLines),
                optOutLabel
        );
    }

    @Nonnull
    public static AnnouncementOptOutState loadAnnouncementState(@Nonnull Path stateFile, @Nullable HytaleLogger logger) {
        Objects.requireNonNull(stateFile, "stateFile");
        synchronized (STATE_LOCK) {
            ensureAnnouncementStateTemplateExists(stateFile, logger);
            AnnouncementStateDocument document = readAnnouncementStateDocument(stateFile, logger);
            if (document == null) {
                document = createDefaultAnnouncementStateDocument();
            }
            return toState(document);
        }
    }

    public static boolean recordOptOut(@Nonnull Path stateFile,
                                       @Nonnull UUID playerUuid,
                                       @Nonnull String announcementId,
                                       @Nullable HytaleLogger logger) {
        Objects.requireNonNull(stateFile, "stateFile");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(announcementId, "announcementId");
        String normalizedAnnouncementId = trimToNull(announcementId);
        if (normalizedAnnouncementId == null) {
            return false;
        }

        synchronized (STATE_LOCK) {
            AnnouncementStateDocument document = readAnnouncementStateDocument(stateFile, logger);
            if (document == null) {
                document = createDefaultAnnouncementStateDocument();
            }
            if (document.optedOutAnnouncementByPlayerUuid == null) {
                document.optedOutAnnouncementByPlayerUuid = new LinkedHashMap<>();
            }
            document.version = CURRENT_VERSION;
            document.optedOutAnnouncementByPlayerUuid.put(playerUuid.toString(), normalizedAnnouncementId);
            return writeDocument(stateFile, document, logger, "save Tamework settings announcement state file ");
        }
    }

    public static boolean hasOptedOut(@Nullable AnnouncementOptOutState state,
                                      @Nullable UUID playerUuid,
                                      @Nullable String announcementId) {
        if (state == null || playerUuid == null) {
            return false;
        }
        String normalizedAnnouncementId = trimToNull(announcementId);
        if (normalizedAnnouncementId == null) {
            return false;
        }
        return normalizedAnnouncementId.equals(state.lastOptedOutAnnouncementIdByPlayerUuid().get(playerUuid));
    }

    public static boolean shouldShowAnnouncement(@Nonnull ResolvedAnnouncement announcement,
                                                 @Nullable AnnouncementOptOutState state,
                                                 @Nullable UUID playerUuid) {
        Objects.requireNonNull(announcement, "announcement");
        if (!announcement.enabled() || playerUuid == null || announcement.announcementId().isBlank()) {
            return false;
        }
        return !hasOptedOut(state, playerUuid, announcement.announcementId());
    }

    private static void ensureAnnouncementTemplateExists(@Nonnull Path announcementFile, @Nullable HytaleLogger logger) {
        if (Files.isRegularFile(announcementFile)) {
            return;
        }
        writeDocument(
                announcementFile,
                createDefaultAnnouncementDocument(),
                logger,
                "save Tamework settings announcement file "
        );
    }

    private static void ensureAnnouncementStateTemplateExists(@Nonnull Path stateFile, @Nullable HytaleLogger logger) {
        if (Files.isRegularFile(stateFile)) {
            return;
        }
        writeDocument(
                stateFile,
                createDefaultAnnouncementStateDocument(),
                logger,
                "save Tamework settings announcement state file "
        );
    }

    @Nullable
    private static AnnouncementDocument readAnnouncementDocument(@Nonnull Path announcementFile,
                                                                 @Nullable HytaleLogger logger) {
        try {
            if (!Files.isRegularFile(announcementFile)) {
                return null;
            }
            String raw = Files.readString(announcementFile, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return GSON.fromJson(raw, AnnouncementDocument.class);
        } catch (JsonSyntaxException syntaxException) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(syntaxException).log(
                        "Invalid Tamework settings announcement JSON; ignoring file " + announcementFile + "."
                );
            }
            return null;
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(
                        "Unable to read Tamework settings announcement file " + announcementFile + "."
                );
            }
            return null;
        }
    }

    @Nullable
    private static AnnouncementStateDocument readAnnouncementStateDocument(@Nonnull Path stateFile,
                                                                           @Nullable HytaleLogger logger) {
        try {
            if (!Files.isRegularFile(stateFile)) {
                return null;
            }
            String raw = Files.readString(stateFile, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return GSON.fromJson(raw, AnnouncementStateDocument.class);
        } catch (JsonSyntaxException syntaxException) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(syntaxException).log(
                        "Invalid Tamework settings announcement state JSON; ignoring file " + stateFile + "."
                );
            }
            return null;
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(
                        "Unable to read Tamework settings announcement state file " + stateFile + "."
                );
            }
            return null;
        }
    }

    private static boolean writeDocument(@Nonnull Path file,
                                         @Nonnull Object document,
                                         @Nullable HytaleLogger logger,
                                         @Nonnull String errorPrefix) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String serialized = GSON.toJson(document) + System.lineSeparator();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, serialized, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log(errorPrefix + file + ".");
            }
            return false;
        }
    }

    @Nonnull
    private static AnnouncementDocument createDefaultAnnouncementDocument() {
        AnnouncementDocument document = new AnnouncementDocument();
        document.version = CURRENT_VERSION;
        document.enabled = true;
        document.useBuiltInAnnouncementId = true;
        document.useBuiltInText = true;
        document.announcementId = BUILT_IN_ANNOUNCEMENT_ID;
        return document;
    }

    @Nonnull
    private static AnnouncementStateDocument createDefaultAnnouncementStateDocument() {
        AnnouncementStateDocument document = new AnnouncementStateDocument();
        document.version = CURRENT_VERSION;
        document.optedOutAnnouncementByPlayerUuid = new LinkedHashMap<>();
        return document;
    }

    @Nonnull
    private static AnnouncementOptOutState toState(@Nonnull AnnouncementStateDocument document) {
        LinkedHashMap<UUID, String> entries = new LinkedHashMap<>();
        if (document.optedOutAnnouncementByPlayerUuid != null) {
            for (Map.Entry<String, String> entry : document.optedOutAnnouncementByPlayerUuid.entrySet()) {
                UUID playerUuid = parseUuid(entry.getKey());
                String announcementId = trimToNull(entry.getValue());
                if (playerUuid == null || announcementId == null) {
                    continue;
                }
                entries.put(playerUuid, announcementId);
            }
        }
        return new AnnouncementOptOutState(Map.copyOf(entries));
    }

    @Nonnull
    private static List<String> sanitizeLines(@Nullable String[] rawLines, @Nonnull String[] fallbackLines) {
        ArrayList<String> lines = new ArrayList<>();
        if (rawLines != null) {
            for (String rawLine : rawLines) {
                String line = trimToNull(rawLine);
                if (line != null) {
                    lines.add(line);
                }
            }
        }
        if (!lines.isEmpty()) {
            return lines;
        }
        ArrayList<String> fallback = new ArrayList<>(fallbackLines.length);
        for (String rawLine : fallbackLines) {
            String line = trimToNull(rawLine);
            if (line != null) {
                fallback.add(line);
            }
        }
        return fallback;
    }

    private static boolean resolveUseBuiltInText(@Nonnull AnnouncementDocument document) {
        if (document.useBuiltInText != null) {
            return document.useBuiltInText;
        }
        return !hasCustomText(document);
    }

    private static boolean hasCustomText(@Nonnull AnnouncementDocument document) {
        if (trimToNull(document.title) != null || trimToNull(document.subtitle) != null || trimToNull(document.optOutLabel) != null) {
            return true;
        }
        if (document.bodyLines == null) {
            return false;
        }
        for (String line : document.bodyLines) {
            if (trimToNull(line) != null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static UUID parseUuid(@Nullable String raw) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return null;
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String firstNonBlank(@Nullable String value, @Nonnull String fallback) {
        String normalized = trimToNull(value);
        return normalized != null ? normalized : fallback;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /** Effective settings announcement resolved from built-in defaults plus universe overrides. */
    public record ResolvedAnnouncement(boolean enabled,
                                       @Nonnull String announcementId,
                                       boolean useBuiltInText,
                                       @Nonnull String title,
                                       @Nonnull String subtitle,
                                       @Nonnull List<String> bodyLines,
                                       @Nonnull String optOutLabel) {
    }

    /** Per-player opt-out state keyed by player UUID. */
    public record AnnouncementOptOutState(@Nonnull Map<UUID, String> lastOptedOutAnnouncementIdByPlayerUuid) {
    }

    private static final class AnnouncementDocument {
        private Integer version;
        private Boolean enabled;
        private Boolean useBuiltInAnnouncementId;
        private Boolean useBuiltInText;
        private String announcementId;
        private String title;
        private String subtitle;
        private String[] bodyLines;
        private String optOutLabel;
    }

    private static final class AnnouncementStateDocument {
        private Integer version;
        private LinkedHashMap<String, String> optedOutAnnouncementByPlayerUuid;
    }
}
