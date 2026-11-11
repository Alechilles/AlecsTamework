package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.access.OwnerGrants;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.access.SharedAccessService.ChangeResult;
import com.alechilles.alecstamework.api.access.AccessGroupProvider;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.commands.TameworkShareRules;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sharing: the viewer's own grants (named players with their level, groups, the add control) and
 * the owners who share with the viewer. Opened from the Sharing button in the companions window.
 *
 * <p>The page changes only the viewer's own grants, or the viewer's place in someone else's named
 * share, so the viewer's identity is its authority. Each action asks {@link SharedAccessService}
 * again when it runs and shows the service's answer; the rendered rows are never trusted. Sharing
 * with a new player or turning a group on first shows the same warning {@code /tw share} shows, and
 * the shared decisions live in {@link TameworkShareRules} so the page and the commands agree.</p>
 *
 * <p>Runs on the viewer's world thread like every custom page. The list of online players is read
 * once per render from player identities only; no live player component is read.</p>
 */
public final class TameworkSharingPage extends InteractiveCustomUIPage<TameworkSharingPage.EventPayload> {
    public static final String UI_PATH = "TameworkSharingPage.ui";
    public static final String ROW_UI_PATH = "TameworkSharingRow.ui";

    private static final String KEY_ACTION = "Action";
    private static final String KEY_VALUE = "@Value";
    private static final String KEY_ADD_PLAYER = "@AddPlayer";
    private static final String KEY_ADD_LEVEL = "@AddLevel";
    private static final String ACTION_CLOSE = "close";
    private static final String ACTION_DONE = "done";
    private static final String ACTION_ADD = "add";
    private static final String ACTION_CONFIRM = "confirm";
    private static final String ACTION_CANCEL = "cancel";
    private static final String PLAYER_LEVEL = "playerLevel:";
    private static final String REMOVE = "remove:";
    private static final String GROUP_LEVEL = "groupLevel:";
    private static final String LEAVE = "leave:";
    private static final String OFF = "off";
    private static final String PLAYER_LIST = "#TameworkSharingPlayerList";
    private static final String GROUP_LIST = "#TameworkSharingGroupList";
    private static final String INBOUND_LIST = "#TameworkSharingInboundList";

    @Nullable
    private final Runnable returnTo;
    /** The share waiting for the viewer to confirm the warning; null when none is shown. */
    @Nullable
    private Pending pending;
    /** The last action's translated result, shown under the lists. */
    private String feedback = "";
    @Nullable
    private String addPlayer;
    private AccessLevel addLevel = AccessLevel.CARETAKER;
    /** Online players offered in the add picker at the last render, by UUID text. */
    private Map<String, String> onlineNames = Map.of();
    private boolean closed;

    /** @param returnTo reopens the page this was opened from (Done), or {@code null} to just close */
    public TameworkSharingPage(@Nonnull PlayerRef playerRef, @Nullable Runnable returnTo) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.returnTo = returnTo;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        commands.append(UI_PATH);
        render(commands, events);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        closed = true;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull EventPayload data) {
        if (closed) {
            return;
        }
        if (data.addPlayer != null) {
            addPlayer = data.addPlayer;
        }
        AccessLevel pickedLevel = TameworkShareRules.parseLevel(data.addLevel);
        if (pickedLevel != null) {
            addLevel = pickedLevel;
        }
        String action = data.action == null ? "" : data.action.trim();
        if (action.isEmpty()) {
            // A picker in the add row changed; it is read again when Share is pressed.
            return;
        }
        switch (action) {
            case ACTION_CLOSE -> {
                closed = true;
                close();
                return;
            }
            case ACTION_DONE -> {
                done(ref, store);
                return;
            }
            default -> {
            }
        }
        if (pending != null) {
            // The warning covers the page: only its two buttons count.
            if (ACTION_CONFIRM.equals(action)) {
                Pending confirmed = pending;
                pending = null;
                confirm(confirmed);
            } else if (ACTION_CANCEL.equals(action)) {
                pending = null;
            } else {
                return;
            }
            refresh();
            return;
        }
        if (ACTION_ADD.equals(action)) {
            add();
        } else if (action.startsWith(PLAYER_LEVEL)) {
            UUID playerId = parseUuid(action.substring(PLAYER_LEVEL.length()));
            if (showsPlayerLevel(playerId, data.value)) {
                // The picker already shows this level: nothing to change or redraw.
                return;
            }
            changePlayerLevel(playerId, data.value);
        } else if (action.startsWith(REMOVE)) {
            remove(parseUuid(action.substring(REMOVE.length())));
        } else if (action.startsWith(GROUP_LEVEL)) {
            String groupId = action.substring(GROUP_LEVEL.length());
            if (showsGroupState(groupId, data.value)) {
                return;
            }
            changeGroup(groupId, data.value);
        } else if (action.startsWith(LEAVE)) {
            leave(parseUuid(action.substring(LEAVE.length())));
        } else {
            return;
        }
        refresh();
    }

    /** True when {@code value} is the player's saved level, so the picker event changes nothing. */
    private boolean showsPlayerLevel(@Nullable UUID playerId, @Nullable String value) {
        OwnerGrants.PlayerGrant grant = playerId == null ? null
                : SharedAccessService.get().grants(owner()).players().get(playerId);
        return grant != null && grant.level() == TameworkShareRules.parseLevel(value);
    }

    /** True when {@code value} is the group's saved state (a level, or off), so the picker event changes nothing. */
    private boolean showsGroupState(@Nonnull String groupId, @Nullable String value) {
        AccessLevel current = SharedAccessService.get().grants(owner()).groups().get(groupId);
        return current == null ? OFF.equals(value) : current == TameworkShareRules.parseLevel(value);
    }

    // ---- Actions. Each one asks the service again and shows its answer.

    private void add() {
        UUID targetId = parseUuid(addPlayer);
        String name = addPlayer == null ? null : onlineNames.get(addPlayer);
        PlayerRef target = targetId == null ? null : onlinePlayer(targetId);
        if (target == null) {
            feedback = name == null ? "" : text("tamework.share.playerNotOnline", name);
            return;
        }
        SharedAccessService service = SharedAccessService.get();
        UUID owner = owner();
        if (service.grants(owner).players().containsKey(targetId)) {
            // Already shared: this only changes the level, which needs no warning.
            grant(target, addLevel, false);
            return;
        }
        ChangeResult refused = TameworkShareRules.addRefusal(service, owner, targetId, addLevel);
        if (refused != null) {
            feedback = refusal(refused, null);
            return;
        }
        pending = new Pending(false, targetId.toString(), target.getUsername(), addLevel);
    }

    private void changePlayerLevel(@Nullable UUID playerId, @Nullable String value) {
        AccessLevel level = TameworkShareRules.parseLevel(value);
        SharedAccessService service = SharedAccessService.get();
        OwnerGrants.PlayerGrant grant = playerId == null ? null : service.grants(owner()).players().get(playerId);
        if (grant == null || level == null) {
            return;
        }
        ChangeResult result = service.grantPlayer(owner(), playerId, null, level);
        String name = TameworkShareRules.grantName(grant);
        feedback = switch (result) {
            case CHANGED -> text("tamework.share.add.changed", name, levelName(level));
            case UNCHANGED -> text("tamework.share.add.unchanged", name, levelName(level));
            default -> refusal(result, null);
        };
        if (result == ChangeResult.CHANGED) {
            PlayerRef target = onlinePlayer(playerId);
            if (target != null) {
                TameworkShareRules.notifyGranted(target, viewerName(), level, false);
            }
        }
    }

    private void remove(@Nullable UUID playerId) {
        SharedAccessService service = SharedAccessService.get();
        OwnerGrants.PlayerGrant grant = playerId == null ? null : service.grants(owner()).players().get(playerId);
        if (grant == null) {
            return;
        }
        String name = TameworkShareRules.grantName(grant);
        feedback = service.revokePlayer(owner(), playerId) == ChangeResult.CHANGED
                ? text("tamework.share.remove.done", name)
                : text("tamework.share.remove.notShared", name);
    }

    private void changeGroup(@Nonnull String groupId, @Nullable String value) {
        if (groupId.isBlank() || value == null) {
            return;
        }
        SharedAccessService service = SharedAccessService.get();
        String name = groupName(service, groupId);
        if (OFF.equals(value)) {
            boolean changed = service.setGroup(owner(), groupId, null) == ChangeResult.CHANGED;
            feedback = text(changed ? "tamework.share.group.off" : "tamework.share.group.notOn", name);
            return;
        }
        AccessLevel level = TameworkShareRules.parseLevel(value);
        if (level == null) {
            return;
        }
        if (!service.grants(owner()).groups().containsKey(groupId)) {
            ChangeResult refused = TameworkShareRules.groupRefusal(service, groupId, level);
            if (refused != null) {
                feedback = refusal(refused, groupId);
                return;
            }
            pending = new Pending(true, groupId, name, level);
            return;
        }
        applyGroup(groupId, name, level);
    }

    private void leave(@Nullable UUID ownerId) {
        SharedAccessService service = SharedAccessService.get();
        if (ownerId == null || !service.isEnabled() || !service.ownersSharingWith(owner()).contains(ownerId)) {
            return;
        }
        TameworkShareRules.LeaveResult left = TameworkShareRules.leave(service, ownerId, owner());
        feedback = text(left.messageKey(), TameworkShareRules.ownerName(ownerId));
    }

    private void confirm(@Nonnull Pending confirmed) {
        if (confirmed.group()) {
            applyGroup(confirmed.id(), confirmed.name(), confirmed.level());
            return;
        }
        UUID targetId = parseUuid(confirmed.id());
        PlayerRef target = targetId == null ? null : onlinePlayer(targetId);
        if (target == null) {
            feedback = text("tamework.share.playerNotOnline", confirmed.name());
            return;
        }
        boolean isNew = !SharedAccessService.get().grants(owner()).players().containsKey(targetId);
        grant(target, confirmed.level(), isNew);
    }

    private void grant(@Nonnull PlayerRef target, @Nonnull AccessLevel level, boolean isNew) {
        String name = target.getUsername();
        ChangeResult result = SharedAccessService.get().grantPlayer(owner(), target.getUuid(), name, level);
        feedback = switch (result) {
            case CHANGED -> text(isNew ? "tamework.share.add.done" : "tamework.share.add.changed", name,
                    levelName(level));
            case UNCHANGED -> text("tamework.share.add.unchanged", name, levelName(level));
            default -> refusal(result, null);
        };
        if (result == ChangeResult.CHANGED) {
            TameworkShareRules.notifyGranted(target, viewerName(), level, isNew);
        }
    }

    private void applyGroup(@Nonnull String groupId, @Nonnull String name, @Nonnull AccessLevel level) {
        ChangeResult result = SharedAccessService.get().setGroup(owner(), groupId, level);
        feedback = switch (result) {
            case CHANGED -> text("tamework.share.group.on", name, levelName(level));
            case UNCHANGED -> text("tamework.share.group.unchanged", name, levelName(level));
            default -> refusal(result, groupId);
        };
    }

    private void done(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        closed = true;
        if (returnTo != null) {
            returnTo.run();
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && player.getPageManager().getCustomPage() == this) {
            close();
        }
    }

    // ---- Rendering.

    private void refresh() {
        if (closed) {
            return;
        }
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        render(commands, events);
        sendUpdate(commands, events, false);
    }

    private void render(@Nonnull UICommandBuilder commands, @Nonnull UIEventBuilder events) {
        SharedAccessService service = SharedAccessService.get();
        UUID owner = owner();
        OwnerGrants grants = service.grants(owner);
        boolean enabled = service.isEnabled();
        List<AccessLevel> levels = grantableLevels(service.maxLevel());

        commands.set("#TameworkSharingStatus.Text", enabled
                ? text("tamework.ui.sharing.status", levelName(service.maxLevel()))
                : text("tamework.share.list.disabled"));
        commands.set("#TameworkSharingPrivate.Visible", !grants.privateCompanions().isEmpty());
        commands.set("#TameworkSharingPrivate.Text",
                text("tamework.share.list.private", grants.privateCompanions().size()));
        commands.set("#TameworkSharingPlayersLabel.Text", text("tamework.ui.sharing.players.section",
                grants.players().size(), SharedAccessService.MAX_PLAYER_GRANTS_PER_OWNER));

        bindPlayers(commands, events, grants, enabled, levels);
        bindAddRow(commands, events, grants, enabled, levels);
        bindGroups(commands, events, service, grants, enabled, levels);
        bindInbound(commands, events, service, owner);

        commands.set("#TameworkSharingFeedback.Text", feedback);
        bindConfirm(commands, events);
        bind(events, "#TameworkSharingCloseButton", ACTION_CLOSE);
        bind(events, "#TameworkSharingDoneButton", ACTION_DONE);
    }

    private void bindPlayers(UICommandBuilder commands, UIEventBuilder events, OwnerGrants grants, boolean enabled,
                             List<AccessLevel> levels) {
        commands.clear(PLAYER_LIST);
        List<OwnerGrants.PlayerGrant> rows = new ArrayList<>(grants.players().values());
        rows.sort(Comparator.comparing(TameworkShareRules::grantName, String.CASE_INSENSITIVE_ORDER));
        commands.set("#TameworkSharingPlayersEmpty.Visible", rows.isEmpty());
        for (int i = 0; i < rows.size(); i++) {
            OwnerGrants.PlayerGrant grant = rows.get(i);
            String root = PLAYER_LIST + "[" + i + "]";
            commands.append(PLAYER_LIST, ROW_UI_PATH);
            commands.set(root + " #Name.Text", TameworkShareRules.grantName(grant));
            // While sharing is off the level cannot change, so only the saved level is offered.
            List<AccessLevel> offered = enabled ? withLevel(levels, grant.level()) : List.of(grant.level());
            bindLevelPicker(commands, events, root, offered, false, TameworkShareRules.levelToken(grant.level()),
                    PLAYER_LEVEL + grant.playerId());
            bindRowButton(commands, events, root, "tamework.ui.sharing.button.remove",
                    REMOVE + grant.playerId());
        }
    }

    private void bindAddRow(UICommandBuilder commands, UIEventBuilder events, OwnerGrants grants, boolean enabled,
                            List<AccessLevel> levels) {
        commands.set("#TameworkSharingAddRow.Visible", enabled);
        if (!enabled) {
            onlineNames = Map.of();
            return;
        }
        // Read once per render from player identities; this is not a tick path.
        Map<String, String> candidates = new LinkedHashMap<>();
        Universe universe = Universe.get();
        if (universe != null) {
            UUID owner = owner();
            universe.getPlayers().stream()
                    .filter(player -> player.getUuid() != null && !owner.equals(player.getUuid())
                            && !grants.players().containsKey(player.getUuid()) && player.getUsername() != null)
                    .sorted(Comparator.comparing(PlayerRef::getUsername, String.CASE_INSENSITIVE_ORDER))
                    .forEach(player -> candidates.put(player.getUuid().toString(), player.getUsername()));
        }
        onlineNames = Map.copyOf(candidates);
        if (addPlayer == null || !candidates.containsKey(addPlayer)) {
            addPlayer = candidates.isEmpty() ? null : candidates.keySet().iterator().next();
        }
        List<DropdownEntryInfo> playerEntries = new ArrayList<>(candidates.size());
        candidates.forEach((id, name) -> playerEntries.add(new DropdownEntryInfo(LocalizableString.fromString(name), id)));
        commands.set("#TameworkSharingAddPlayer.Entries", playerEntries);
        if (addPlayer != null) {
            commands.set("#TameworkSharingAddPlayer.Value", addPlayer);
        }
        if (!levels.contains(addLevel)) {
            addLevel = levels.getFirst();
        }
        commands.set("#TameworkSharingAddLevel.Entries", levelEntries(levels, false));
        commands.set("#TameworkSharingAddLevel.Value", TameworkShareRules.levelToken(addLevel));
        commands.set("#TameworkSharingAddButton.Disabled", candidates.isEmpty());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TameworkSharingAddPlayer",
                EventData.of(KEY_ADD_PLAYER, "#TameworkSharingAddPlayer.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TameworkSharingAddLevel",
                EventData.of(KEY_ADD_LEVEL, "#TameworkSharingAddLevel.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TameworkSharingAddButton",
                EventData.of(KEY_ACTION, ACTION_ADD)
                        .append(KEY_ADD_PLAYER, "#TameworkSharingAddPlayer.Value")
                        .append(KEY_ADD_LEVEL, "#TameworkSharingAddLevel.Value"), false);
    }

    private void bindGroups(UICommandBuilder commands, UIEventBuilder events, SharedAccessService service,
                            OwnerGrants grants, boolean enabled, List<AccessLevel> levels) {
        commands.clear(GROUP_LIST);
        // Every registered group, then any saved group whose provider is gone, so it can still be turned off.
        List<String> groupIds = new ArrayList<>();
        service.groupProviders().stream()
                .sorted(Comparator.comparing(AccessGroupProvider::id))
                .forEach(provider -> groupIds.add(provider.id()));
        grants.groups().keySet().stream().filter(id -> !groupIds.contains(id)).sorted().forEach(groupIds::add);
        commands.set("#TameworkSharingGroupsEmpty.Visible", groupIds.isEmpty());
        for (int i = 0; i < groupIds.size(); i++) {
            String groupId = groupIds.get(i);
            AccessLevel current = grants.groups().get(groupId);
            String root = GROUP_LIST + "[" + i + "]";
            commands.append(GROUP_LIST, ROW_UI_PATH);
            commands.set(root + " #Name.Text", groupName(service, groupId));
            boolean canTurnOn = enabled && service.groupProvider(groupId) != null;
            List<AccessLevel> offered = canTurnOn ? withLevel(levels, current)
                    : current == null ? List.of() : List.of(current);
            bindLevelPicker(commands, events, root, offered, true,
                    current == null ? OFF : TameworkShareRules.levelToken(current), GROUP_LEVEL + groupId);
        }
    }

    private void bindInbound(UICommandBuilder commands, UIEventBuilder events, SharedAccessService service,
                             UUID viewer) {
        commands.clear(INBOUND_LIST);
        int row = 0;
        for (UUID ownerId : service.ownersSharingWith(viewer)) {
            AccessLevel level = service.level(ownerId, viewer, null);
            if (!level.isGrantable()) {
                continue;
            }
            String root = INBOUND_LIST + "[" + row + "]";
            commands.append(INBOUND_LIST, ROW_UI_PATH);
            commands.set(root + " #Name.Text", TameworkShareRules.ownerName(ownerId));
            commands.set(root + " #LevelText.Visible", true);
            commands.set(root + " #LevelText.Text", levelName(level));
            // Only a named share can be left; group membership belongs to the group.
            if (service.grants(ownerId).players().containsKey(viewer)) {
                bindRowButton(commands, events, root, "tamework.ui.sharing.button.leave", LEAVE + ownerId);
            }
            row++;
        }
        commands.set("#TameworkSharingInboundEmpty.Visible", row == 0);
    }

    private void bindLevelPicker(UICommandBuilder commands, UIEventBuilder events, String root,
                                 List<AccessLevel> levels, boolean withOff, String value, String action) {
        String picker = root + " #Level";
        commands.set(picker + ".Visible", true);
        commands.set(picker + ".Entries", levelEntries(levels, withOff));
        commands.set(picker + ".Value", value);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, picker,
                EventData.of(KEY_ACTION, action).append(KEY_VALUE, picker + ".Value"), false);
    }

    private void bindRowButton(UICommandBuilder commands, UIEventBuilder events, String root, String labelKey,
                               String action) {
        commands.set(root + " #Action.Visible", true);
        commands.set(root + " #Action.Text", text(labelKey));
        bind(events, root + " #Action", action);
    }

    private void bindConfirm(UICommandBuilder commands, UIEventBuilder events) {
        commands.set("#TameworkSharingConfirm.Visible", pending != null);
        if (pending == null) {
            return;
        }
        String level = levelName(pending.level());
        commands.set("#TameworkSharingConfirmText.Text", pending.group()
                ? text("tamework.ui.sharing.confirm.group", pending.name(), level)
                : text("tamework.ui.sharing.confirm.player", pending.name(), level));
        bind(events, "#TameworkSharingConfirmButton", ACTION_CONFIRM);
        bind(events, "#TameworkSharingConfirmCancel", ACTION_CANCEL);
    }

    private List<DropdownEntryInfo> levelEntries(List<AccessLevel> levels, boolean withOff) {
        List<DropdownEntryInfo> entries = new ArrayList<>(levels.size() + 1);
        if (withOff) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(text("tamework.ui.sharing.groups.off")), OFF));
        }
        for (AccessLevel level : levels) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(levelName(level)),
                    TameworkShareRules.levelToken(level)));
        }
        return entries;
    }

    /** Grantable levels up to the server's highest. */
    private static List<AccessLevel> grantableLevels(@Nonnull AccessLevel max) {
        List<AccessLevel> levels = new ArrayList<>(3);
        for (AccessLevel level : AccessLevel.values()) {
            if (level.isGrantable() && level.compareTo(max) <= 0) {
                levels.add(level);
            }
        }
        return levels;
    }

    /** {@code levels} plus a saved level above the server's highest, so the picker can still show it. */
    private static List<AccessLevel> withLevel(List<AccessLevel> levels, @Nullable AccessLevel saved) {
        if (saved == null || levels.contains(saved)) {
            return levels;
        }
        List<AccessLevel> out = new ArrayList<>(levels);
        out.add(saved);
        return out;
    }

    // ---- Small helpers.

    @Nonnull
    private UUID owner() {
        return playerRef.getUuid();
    }

    @Nonnull
    private String viewerName() {
        String name = playerRef.getUsername();
        return name == null ? owner().toString().substring(0, 8) : name;
    }

    private String text(String key, Object... args) {
        return LocalizedText.format(playerRef, key, args);
    }

    private String levelName(AccessLevel level) {
        return LocalizedText.resolve(playerRef, TameworkShareRules.levelKey(level));
    }

    private String refusal(ChangeResult result, @Nullable String groupId) {
        Object arg = switch (result) {
            case LEVEL_NOT_ALLOWED -> levelName(SharedAccessService.get().maxLevel());
            case TOO_MANY_PLAYERS -> SharedAccessService.MAX_PLAYER_GRANTS_PER_OWNER;
            case UNKNOWN_GROUP -> groupId == null ? "" : groupId;
            default -> "";
        };
        return text(TameworkShareRules.refusalKey(result), arg);
    }

    /** The group's translated name when its provider is registered, else its id. */
    private String groupName(SharedAccessService service, String groupId) {
        AccessGroupProvider provider = service.groupProvider(groupId);
        return provider == null ? groupId : LocalizedText.resolve(playerRef, provider.displayNameKey());
    }

    @Nullable
    private static PlayerRef onlinePlayer(@Nonnull UUID playerId) {
        Universe universe = Universe.get();
        return universe == null ? null : universe.getPlayer(playerId);
    }

    @Nullable
    private static UUID parseUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void bind(UIEventBuilder events, String selector, String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of(KEY_ACTION, action), false);
    }

    /** A share waiting for confirmation: a player by UUID text, or a group by id. */
    private record Pending(boolean group, String id, String name, AccessLevel level) {
    }

    /** Event payload: the action, a row picker's value, and the add row's two pickers. */
    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .<String>append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (x, v) -> x.action = v, x -> x.action)
                .add()
                .<String>append(new KeyedCodec<>(KEY_VALUE, Codec.STRING), (x, v) -> x.value = v, x -> x.value)
                .add()
                .<String>append(new KeyedCodec<>(KEY_ADD_PLAYER, Codec.STRING), (x, v) -> x.addPlayer = v,
                        x -> x.addPlayer)
                .add()
                .<String>append(new KeyedCodec<>(KEY_ADD_LEVEL, Codec.STRING), (x, v) -> x.addLevel = v,
                        x -> x.addLevel)
                .add()
                .build();

        private String action;
        private String value;
        private String addPlayer;
        private String addLevel;
    }
}
