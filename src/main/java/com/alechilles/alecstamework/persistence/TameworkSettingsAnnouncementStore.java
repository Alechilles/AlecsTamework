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
    public static final String WELCOME_ANNOUNCEMENT_ID = "welcome-v1";
    public static final String BUILT_IN_ANNOUNCEMENT_ID = "persistence-rework-v3";
    public static final String WELCOME_TITLE_KEY = "tamework.ui.settingsAnnouncement.welcome.title";
    public static final String WELCOME_SUBTITLE_KEY = "tamework.ui.settingsAnnouncement.welcome.subtitle";
    public static final String WELCOME_OPT_OUT_LABEL_KEY = "tamework.ui.settingsAnnouncement.welcome.optOut";
    public static final String[] WELCOME_BODY_LINE_KEYS = {
            "tamework.ui.settingsAnnouncement.welcome.body.intro",
            "tamework.ui.settingsAnnouncement.welcome.body.settings",
            "tamework.ui.settingsAnnouncement.welcome.body.scope"
    };
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
    private static final String DEFAULT_TITLE = "Alec's Tamework 3.0: Persistence Rework";
    private static final String DEFAULT_SUBTITLE =
            "Tamework's persistence system has been completely reworked.";
    private static final String DEFAULT_OPT_OUT_LABEL = "Don't show again until next announcement";
    private static final String DEFAULT_WELCOME_TITLE = "Welcome to Alec's Tamework";
    private static final String DEFAULT_WELCOME_SUBTITLE =
            "Tamework adds shared companion, progression, and server settings for Alec's mods.";
    private static final String DEFAULT_WELCOME_OPT_OUT_LABEL = "Don't show this again";
    private static final String[] DEFAULT_WELCOME_BODY_LINES = {
            "Thanks for installing Alec's Tamework. This framework powers shared features used by Alec's mods, including companion ownership, commands, needs, happiness, breeding, traits, travel, revival, telemetry, and optional integration behavior.",
            "The /tw settings menu lets server owners choose how much of that experience is active. You can review presets, ownership protection, capture and linking rules, population limits, needs damage, happiness, passive breeding, gendered breeding, traits, revival, travel, and telemetry from one place.",
            "This welcome appears the first time an eligible player uses Tamework. Future version-specific notices only appear for players who have already used Tamework on an older version."
    };
    private static final String[] DEFAULT_BODY_LINES = {
            "The new persistence system is designed to make companion data more reliable, but a major rework can still have hiccups. Please keep an eye out for unexpected companion, capture, storage, or restore behavior.",
            "If you run into an issue, run /tw debug persistence export before restarting if possible. This creates a redacted support package under Data/diagnostics.",
            "Then create an issue in the Discord server's Issues and Bug Reports channel: https://discord.gg/uP5bNTVSze. Include your full server log and the exported package."
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

    public static boolean recordAnnouncementSeen(@Nonnull Path stateFile,
                                                 @Nonnull UUID playerUuid,
                                                 @Nonnull String announcementId,
                                                 @Nullable String tameworkVersion,
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
            if (document.lastShownAnnouncementByPlayerUuid == null) {
                document.lastShownAnnouncementByPlayerUuid = new LinkedHashMap<>();
            }
            document.version = CURRENT_VERSION;
            document.lastShownAnnouncementByPlayerUuid.put(playerUuid.toString(), normalizedAnnouncementId);
            String normalizedVersion = trimToNull(tameworkVersion);
            if (normalizedVersion != null) {
                if (document.lastSeenTameworkVersionByPlayerUuid == null) {
                    document.lastSeenTameworkVersionByPlayerUuid = new LinkedHashMap<>();
                }
                document.lastSeenTameworkVersionByPlayerUuid.put(playerUuid.toString(), normalizedVersion);
            }
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

    @Nullable
    public static ResolvedAnnouncement selectAnnouncementForPlayer(@Nonnull ResolvedAnnouncement updateAnnouncement,
                                                                   @Nullable AnnouncementOptOutState state,
                                                                   @Nullable UUID playerUuid,
                                                                   @Nullable String currentTameworkVersion,
                                                                   boolean ignoreOptOutState) {
        Objects.requireNonNull(updateAnnouncement, "updateAnnouncement");
        if (playerUuid == null) {
            return null;
        }
        if (shouldShowWelcomeAnnouncement(state, playerUuid, ignoreOptOutState)) {
            return createWelcomeAnnouncement();
        }
        if (shouldShowUpdateAnnouncement(updateAnnouncement, state, playerUuid, currentTameworkVersion, ignoreOptOutState)) {
            return updateAnnouncement;
        }
        return ignoreOptOutState ? updateAnnouncement : null;
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
        document.lastShownAnnouncementByPlayerUuid = new LinkedHashMap<>();
        document.lastSeenTameworkVersionByPlayerUuid = new LinkedHashMap<>();
        return document;
    }

    @Nonnull
    private static ResolvedAnnouncement createWelcomeAnnouncement() {
        return new ResolvedAnnouncement(
                true,
                WELCOME_ANNOUNCEMENT_ID,
                true,
                DEFAULT_WELCOME_TITLE,
                DEFAULT_WELCOME_SUBTITLE,
                List.of(DEFAULT_WELCOME_BODY_LINES),
                DEFAULT_WELCOME_OPT_OUT_LABEL
        );
    }

    @Nonnull
    private static AnnouncementOptOutState toState(@Nonnull AnnouncementStateDocument document) {
        return new AnnouncementOptOutState(
                toUuidStringMap(document.optedOutAnnouncementByPlayerUuid),
                toUuidStringMap(document.lastShownAnnouncementByPlayerUuid),
                toUuidStringMap(document.lastSeenTameworkVersionByPlayerUuid)
        );
    }

    @Nonnull
    private static Map<UUID, String> toUuidStringMap(@Nullable Map<String, String> rawEntries) {
        LinkedHashMap<UUID, String> entries = new LinkedHashMap<>();
        if (rawEntries != null) {
            for (Map.Entry<String, String> entry : rawEntries.entrySet()) {
                UUID playerUuid = parseUuid(entry.getKey());
                String value = trimToNull(entry.getValue());
                if (playerUuid == null || value == null) {
                    continue;
                }
                entries.put(playerUuid, value);
            }
        }
        return Map.copyOf(entries);
    }

    private static boolean shouldShowWelcomeAnnouncement(@Nullable AnnouncementOptOutState state,
                                                         @Nonnull UUID playerUuid,
                                                         boolean ignoreOptOutState) {
        if (!ignoreOptOutState && hasOptedOut(state, playerUuid, WELCOME_ANNOUNCEMENT_ID)) {
            return false;
        }
        return !hasAnnouncementHistory(state, playerUuid);
    }

    private static boolean shouldShowUpdateAnnouncement(@Nonnull ResolvedAnnouncement updateAnnouncement,
                                                        @Nullable AnnouncementOptOutState state,
                                                        @Nonnull UUID playerUuid,
                                                        @Nullable String currentTameworkVersion,
                                                        boolean ignoreOptOutState) {
        if (!ignoreOptOutState && !shouldShowAnnouncement(updateAnnouncement, state, playerUuid)) {
            return false;
        }
        String lastShownAnnouncementId = state == null
                ? null
                : state.lastShownAnnouncementIdByPlayerUuid().get(playerUuid);
        if (updateAnnouncement.announcementId().equals(lastShownAnnouncementId)) {
            return false;
        }
        String previousVersion = state == null ? null : state.lastSeenTameworkVersionByPlayerUuid().get(playerUuid);
        return previousVersion == null
                ? hasAnnouncementHistory(state, playerUuid)
                : isOlderVersion(previousVersion, currentTameworkVersion);
    }

    private static boolean hasAnnouncementHistory(@Nullable AnnouncementOptOutState state, @Nonnull UUID playerUuid) {
        return state != null
                && (state.lastOptedOutAnnouncementIdByPlayerUuid().containsKey(playerUuid)
                || state.lastShownAnnouncementIdByPlayerUuid().containsKey(playerUuid)
                || state.lastSeenTameworkVersionByPlayerUuid().containsKey(playerUuid));
    }

    private static boolean isOlderVersion(@Nullable String previousVersion, @Nullable String currentVersion) {
        String previous = trimToNull(previousVersion);
        String current = trimToNull(currentVersion);
        if (previous == null || current == null || previous.equals(current)) {
            return false;
        }
        int compare = compareDottedVersions(previous, current);
        return compare < 0 || (compare == 0 && !previous.equals(current));
    }

    private static int compareDottedVersions(@Nonnull String left, @Nonnull String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            int leftValue = index < leftParts.length ? parseVersionPart(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parseVersionPart(rightParts[index]) : 0;
            int compare = Integer.compare(leftValue, rightValue);
            if (compare != 0) {
                return compare;
            }
        }
        return 0;
    }

    private static int parseVersionPart(@Nonnull String rawPart) {
        int end = 0;
        while (end < rawPart.length() && Character.isDigit(rawPart.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(rawPart.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
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
    public record AnnouncementOptOutState(@Nonnull Map<UUID, String> lastOptedOutAnnouncementIdByPlayerUuid,
                                          @Nonnull Map<UUID, String> lastShownAnnouncementIdByPlayerUuid,
                                          @Nonnull Map<UUID, String> lastSeenTameworkVersionByPlayerUuid) {
        public AnnouncementOptOutState(@Nonnull Map<UUID, String> lastOptedOutAnnouncementIdByPlayerUuid) {
            this(lastOptedOutAnnouncementIdByPlayerUuid, Map.of(), Map.of());
        }

        public AnnouncementOptOutState {
            lastOptedOutAnnouncementIdByPlayerUuid = Map.copyOf(lastOptedOutAnnouncementIdByPlayerUuid);
            lastShownAnnouncementIdByPlayerUuid = Map.copyOf(lastShownAnnouncementIdByPlayerUuid);
            lastSeenTameworkVersionByPlayerUuid = Map.copyOf(lastSeenTameworkVersionByPlayerUuid);
        }
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
        private LinkedHashMap<String, String> lastShownAnnouncementByPlayerUuid;
        private LinkedHashMap<String, String> lastSeenTameworkVersionByPlayerUuid;
    }
}
