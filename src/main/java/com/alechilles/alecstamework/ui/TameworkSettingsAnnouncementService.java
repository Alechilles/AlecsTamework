package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.commands.TameworkConfigPermission;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.persistence.TameworkSettingsAnnouncementStore;
import com.alechilles.alecstamework.persistence.TameworkSettingsAnnouncementStore.AnnouncementOptOutState;
import com.alechilles.alecstamework.persistence.TameworkSettingsAnnouncementStore.ResolvedAnnouncement;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opens the Tamework settings announcement once per login session for eligible players.
 * While the world still needs its old companion saves converted, server admins, operators and
 * the local singleplayer owner get the conversion notice instead, on every login: it has no
 * opt-out and no seen state.
 */
public final class TameworkSettingsAnnouncementService {
    private static final String MIGRATION_TITLE_KEY = "tamework.companions.persistence.migrationRequired.title";

    private final Tamework plugin;
    private final Set<UUID> attemptedThisSession = ConcurrentHashMap.newKeySet();
    private final Set<UUID> migrationNoticeShownThisSession = ConcurrentHashMap.newKeySet();
    /** Set once during plugin setup, before any player event; null when no conversion is needed. */
    @Nullable private volatile CompanionStorage.LegacyKind migrationNotice;

    public TameworkSettingsAnnouncementService(@Nonnull Tamework plugin) {
        this.plugin = plugin;
    }

    public void onPlayerConnect(@Nullable PlayerConnectEvent event) {
        clearSessionState(event != null && event.getPlayerRef() != null ? event.getPlayerRef().getUuid() : null);
    }

    public void onPlayerDisconnect(@Nullable PlayerDisconnectEvent event) {
        clearSessionState(event != null && event.getPlayerRef() != null ? event.getPlayerRef().getUuid() : null);
    }

    /** Makes admins, operators and the local singleplayer owner get the conversion notice for {@code kind} on every login from now on. */
    public void requireMigrationNotice(@Nonnull CompanionStorage.LegacyKind kind) {
        this.migrationNotice = kind;
    }

    /**
     * The conversion notice this player must see, or {@code null}. Only whether a conversion is
     * pending and who the player is decide it; seen and opt-out state do not. The audience is the
     * people who can convert the world: server admins, operators with Tamework settings access,
     * and the local singleplayer owner. Regular players get nothing.
     */
    @Nullable
    static CompanionStorage.LegacyKind migrationNoticeFor(@Nullable CompanionStorage.LegacyKind pending,
                                                         boolean serverAdmin,
                                                         boolean settingsAccess,
                                                         boolean localSingleplayerOwner) {
        return serverAdmin || settingsAccess || localSingleplayerOwner ? pending : null;
    }

    /**
     * The conversion notice this player must see now, or {@code null}. Safe in the connect event:
     * it needs the player's identity only, not a loaded entity.
     */
    @Nullable
    public CompanionStorage.LegacyKind migrationNoticeFor(@Nullable PlayerRef playerRef) {
        CompanionStorage.LegacyKind pending = migrationNotice;
        if (pending == null || playerRef == null || playerRef.getUuid() == null) {
            return null;
        }
        PermissionsModule permissions = PermissionsModule.get();
        boolean serverAdmin = permissions != null && permissions.getGroupsForUser(playerRef.getUuid())
                .contains(HytalePermissionsProvider.GROUP_ADMIN);
        boolean localOwner = SingleplayerModule.get() != null && SingleplayerModule.isOwner(playerRef);
        return migrationNoticeFor(pending, serverAdmin, TameworkConfigPermission.hasAccess(playerRef), localOwner);
    }

    /** The localization key, without the {@code server.} prefix, of the notice text for {@code kind}. */
    @Nonnull
    public static String migrationNoticeKey(@Nonnull CompanionStorage.LegacyKind kind) {
        return "tamework.companions.persistence.migrationRequired."
                + (kind == CompanionStorage.LegacyKind.LEGACY_2X ? "legacy2x" : "legacy3x4x");
    }

    /**
     * Opens the conversion notice for its audience, once per login session. Runs where the
     * announcement opens: in the PlayerReadyEvent, with the event's own player and store.
     */
    public void onPlayerReadyMigrationNotice(@Nullable PlayerReadyEvent event) {
        if (event == null || event.getPlayer() == null || event.getPlayerRef() == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerUuid = player.getUuid();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        CompanionStorage.LegacyKind kind = migrationNoticeFor(uiPlayerRef);
        if (kind == null || playerUuid == null || !migrationNoticeShownThisSession.add(playerUuid)) {
            return;
        }
        Ref<EntityStore> playerRef = event.getPlayerRef();
        Store<EntityStore> store = playerRef.getStore();
        if (store == null || !playerRef.isValid() || player.getPageManager() == null
                || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return;
        }
        TameworkSettingsAnnouncementPage page = TameworkSettingsAnnouncementPage.notice(
                uiPlayerRef,
                LocalizedText.resolve(uiPlayerRef, MIGRATION_TITLE_KEY),
                LocalizedText.format(uiPlayerRef, migrationNoticeKey(kind), kind.converterVersion()));
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
        } catch (Throwable throwable) {
            plugin.getLogger().at(Level.WARNING).withCause(throwable)
                    .log("Failed to open the Tamework world conversion notice for player " + playerUuid + ".");
        }
    }

    public void onPlayerReady(@Nullable PlayerReadyEvent event) {
        if (event == null || event.getPlayer() == null || event.getPlayerRef() == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerUuid = player.getUuid();
        if (playerUuid == null || !attemptedThisSession.add(playerUuid)) {
            return;
        }
        // The conversion notice takes this login's popup; other news waits for a later login.
        if (migrationNoticeFor(player.getPlayerRef()) != null) {
            return;
        }
        Store<EntityStore> store = event.getPlayerRef().getStore();
        if (store == null) {
            return;
        }
        openAnnouncement(playerUuid, event.getPlayerRef(), store, player, true, false);
    }

    @Nullable
    public String openAnnouncementNow(@Nonnull Ref<EntityStore> playerRef,
                                      @Nonnull Store<EntityStore> store,
                                      @Nonnull Player player) {
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return LocalizedText.resolve(player, "tamework.ui.settingsAnnouncement.openUnavailable");
        }
        return openAnnouncement(playerUuid, playerRef, store, player, false, true);
    }

    @Nullable
    private String openAnnouncement(@Nonnull UUID playerUuid,
                                    @Nonnull Ref<EntityStore> playerRef,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull Player player,
                                    boolean respectEnabled,
                                    boolean ignoreOptOutState) {
        if (!playerRef.isValid()) {
            return respectEnabled ? null : "Unable to open Tamework news right now.";
        }
        if (player.getPageManager() == null) {
            return respectEnabled ? null : "Unable to open Tamework news right now.";
        }

        ResolvedAnnouncement announcement = TameworkSettingsAnnouncementStore.loadResolvedAnnouncement(
                resolveAnnouncementConfigFile(),
                plugin.getLogger()
        );
        if (respectEnabled && !announcement.enabled()) {
            return null;
        }
        String currentTameworkVersion = resolveCurrentTameworkVersion();
        if (!ignoreOptOutState) {
            AnnouncementOptOutState state = TameworkSettingsAnnouncementStore.loadAnnouncementState(
                    resolveAnnouncementStateFile(),
                    plugin.getLogger()
            );
            announcement = TameworkSettingsAnnouncementStore.selectAnnouncementForPlayer(
                    announcement,
                    state,
                    playerUuid,
                    currentTameworkVersion,
                    false
            );
            if (announcement == null) {
                return null;
            }
        } else {
            AnnouncementOptOutState state = TameworkSettingsAnnouncementStore.loadAnnouncementState(
                    resolveAnnouncementStateFile(),
                    plugin.getLogger()
            );
            ResolvedAnnouncement selected = TameworkSettingsAnnouncementStore.selectAnnouncementForPlayer(
                    announcement,
                    state,
                    playerUuid,
                    currentTameworkVersion,
                    true
            );
            if (selected != null) {
                announcement = selected;
            }
        }

        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return respectEnabled ? null : "Unable to open Tamework news right now.";
        }
        if (!TameworkSettingsPageService.hasAccess(uiPlayerRef, uiPlayerRef)) {
            return respectEnabled ? null : "You do not have permission to use /tw news.";
        }

        ResolvedAnnouncement selectedAnnouncement = announcement;
        AnnouncementCopy copy = resolveCopy(uiPlayerRef, selectedAnnouncement);

        TameworkSettingsAnnouncementPage page = new TameworkSettingsAnnouncementPage(
                uiPlayerRef,
                copy.title(),
                copy.subtitle(),
                copy.bodyText(),
                copy.optOutLabel(),
                suppress -> onReviewSettings(playerRef, store, uiPlayerRef, playerUuid, selectedAnnouncement.announcementId(), suppress),
                suppress -> onDismissAnnouncement(playerUuid, selectedAnnouncement.announcementId(), suppress)
        );
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
            recordAnnouncementSeen(playerUuid, selectedAnnouncement.announcementId(), currentTameworkVersion);
            plugin.getTelemetryEvents().recordUsage(
                    "settings_announcement_opened",
                    TameworkTelemetryEvents.featureContext("settings", "settings_announcement", "settings_announcement")
                            .operation("open")
                            .detail("Opened Tamework settings announcement.")
                            .detail("source", "announcement")
                            .build()
            );
            return null;
        } catch (Throwable throwable) {
            plugin.getTelemetryEvents().recordError(
                    "ui_page_open_failed",
                    throwable,
                    TameworkTelemetryContext.uiPage(
                            "TameworkSettingsAnnouncementPage",
                            "announcement",
                            "open",
                            "Failed to open Tamework settings announcement."
                    ).build()
            );
            return respectEnabled ? null : "Unable to open Tamework news right now.";
        }
    }

    @Nonnull
    private static AnnouncementCopy resolveCopy(@Nonnull PlayerRef playerRef, @Nonnull ResolvedAnnouncement announcement) {
        if (!announcement.useBuiltInText()) {
            return new AnnouncementCopy(
                    announcement.title(),
                    announcement.subtitle(),
                    String.join("\n\n", announcement.bodyLines()),
                    announcement.optOutLabel()
            );
        }
        String title = resolveBuiltIn(playerRef, titleKey(announcement), announcement.title());
        String subtitle = resolveBuiltIn(playerRef, subtitleKey(announcement), announcement.subtitle());
        String optOutLabel = resolveBuiltIn(playerRef, optOutKey(announcement), announcement.optOutLabel());
        String bodyText = resolveBodyText(playerRef, bodyLineKeys(announcement), announcement.bodyLines());
        return new AnnouncementCopy(title, subtitle, bodyText, optOutLabel);
    }

    @Nonnull
    private static String resolveBuiltIn(@Nonnull PlayerRef playerRef, @Nonnull String key, @Nonnull String fallback) {
        String resolved = LocalizedText.resolve(playerRef, key);
        return resolved.equals(key) || resolved.isBlank() ? fallback : resolved;
    }

    @Nonnull
    private static String resolveBodyText(@Nonnull PlayerRef playerRef,
                                          @Nonnull String[] bodyLineKeys,
                                          @Nonnull java.util.List<String> fallbackBodyLines) {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < bodyLineKeys.length; index++) {
            if (index > 0) {
                body.append("\n\n");
            }
            String fallback = index < fallbackBodyLines.size() ? fallbackBodyLines.get(index) : "";
            body.append(resolveBuiltIn(playerRef, bodyLineKeys[index], fallback));
        }
        return body.toString();
    }

    @Nonnull
    private static String titleKey(@Nonnull ResolvedAnnouncement announcement) {
        return TameworkSettingsAnnouncementStore.WELCOME_ANNOUNCEMENT_ID.equals(announcement.announcementId())
                ? TameworkSettingsAnnouncementStore.WELCOME_TITLE_KEY
                : TameworkSettingsAnnouncementStore.BUILT_IN_TITLE_KEY;
    }

    @Nonnull
    private static String subtitleKey(@Nonnull ResolvedAnnouncement announcement) {
        return TameworkSettingsAnnouncementStore.WELCOME_ANNOUNCEMENT_ID.equals(announcement.announcementId())
                ? TameworkSettingsAnnouncementStore.WELCOME_SUBTITLE_KEY
                : TameworkSettingsAnnouncementStore.BUILT_IN_SUBTITLE_KEY;
    }

    @Nonnull
    private static String optOutKey(@Nonnull ResolvedAnnouncement announcement) {
        return TameworkSettingsAnnouncementStore.WELCOME_ANNOUNCEMENT_ID.equals(announcement.announcementId())
                ? TameworkSettingsAnnouncementStore.WELCOME_OPT_OUT_LABEL_KEY
                : TameworkSettingsAnnouncementStore.BUILT_IN_OPT_OUT_LABEL_KEY;
    }

    @Nonnull
    private static String[] bodyLineKeys(@Nonnull ResolvedAnnouncement announcement) {
        return TameworkSettingsAnnouncementStore.WELCOME_ANNOUNCEMENT_ID.equals(announcement.announcementId())
                ? TameworkSettingsAnnouncementStore.WELCOME_BODY_LINE_KEYS
                : TameworkSettingsAnnouncementStore.BUILT_IN_BODY_LINE_KEYS;
    }

    private void onDismissAnnouncement(@Nonnull UUID playerUuid,
                                       @Nonnull String announcementId,
                                       boolean suppressUntilNextAnnouncement) {
        persistOptOutIfRequested(playerUuid, announcementId, suppressUntilNextAnnouncement);
    }

    private void onReviewSettings(@Nonnull Ref<EntityStore> ref,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull PlayerRef playerRef,
                                  @Nonnull UUID playerUuid,
                                  @Nonnull String announcementId,
        boolean suppressUntilNextAnnouncement) {
        persistOptOutIfRequested(playerUuid, announcementId, suppressUntilNextAnnouncement);
        String error = TameworkSettingsPageService.openSettingsPage(ref, store, "announcement", "settings_announcement");
        if (error != null) {
            plugin.getTelemetryEvents().recordError(
                    "settings_announcement_review_failed",
                    null,
                    TameworkTelemetryEvents.featureContext("settings", "settings_announcement", "settings_announcement")
                            .operation("review_settings")
                            .detail(error)
                            .detail("source", "announcement")
                            .build()
            );
            playerRef.sendMessage(Message.raw(error));
            return;
        }
        plugin.getTelemetryEvents().recordUsage(
                "settings_announcement_reviewed",
                TameworkTelemetryEvents.featureContext("settings", "settings_announcement", "settings_announcement")
                        .operation("review_settings")
                        .detail("Opened settings from announcement.")
                        .detail("source", "announcement")
                        .build()
        );
    }

    private void persistOptOutIfRequested(@Nonnull UUID playerUuid,
                                          @Nonnull String announcementId,
                                          boolean suppressUntilNextAnnouncement) {
        if (!suppressUntilNextAnnouncement) {
            return;
        }
        if (TameworkSettingsAnnouncementStore.recordOptOut(
                resolveAnnouncementStateFile(),
                playerUuid,
                announcementId,
                plugin.getLogger()
        )) {
            return;
        }
        plugin.getTelemetryEvents().recordError(
                "settings_announcement_opt_out_persist_failed",
                null,
                TameworkTelemetryEvents.featureContext("settings", "settings_announcement", "settings_announcement")
                        .operation("persist_opt_out")
                        .detail("Failed to persist announcement opt-out.")
                        .detail("source", "announcement")
                        .build()
        );
        plugin.getLogger().at(Level.WARNING).log(
                "Failed to persist Tamework settings announcement opt-out for player " + playerUuid + "."
        );
    }

    private void recordAnnouncementSeen(@Nonnull UUID playerUuid,
                                        @Nonnull String announcementId,
                                        @Nonnull String currentTameworkVersion) {
        if (TameworkSettingsAnnouncementStore.recordAnnouncementSeen(
                resolveAnnouncementStateFile(),
                playerUuid,
                announcementId,
                currentTameworkVersion,
                plugin.getLogger()
        )) {
            return;
        }
        plugin.getLogger().at(Level.WARNING).log(
                "Failed to persist Tamework settings announcement seen state for player " + playerUuid + "."
        );
    }

    private void clearSessionState(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        attemptedThisSession.remove(playerUuid);
        migrationNoticeShownThisSession.remove(playerUuid);
    }

    @Nonnull
    private Path resolveAnnouncementConfigFile() {
        return TameworkSettingsAnnouncementStore.resolveAnnouncementConfigFile(plugin);
    }

    @Nonnull
    private Path resolveAnnouncementStateFile() {
        return TameworkSettingsAnnouncementStore.resolveAnnouncementStateFile(plugin);
    }

    @Nonnull
    private String resolveCurrentTameworkVersion() {
        PluginManifest manifest = plugin.getManifest();
        if (manifest == null) {
            return "?";
        }
        Semver version = manifest.getVersion();
        return version == null ? "?" : version.toString();
    }

    private record AnnouncementCopy(@Nonnull String title,
                                    @Nonnull String subtitle,
                                    @Nonnull String bodyText,
                                    @Nonnull String optOutLabel) {
    }
}
