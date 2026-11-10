package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.companioncard.CardRenameAvailability;
import com.alechilles.alecstamework.companioncard.CompanionCardInteractions;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.items.CommandTargetHudViewModel;
import com.alechilles.alecstamework.items.components.TameworkCompanionCardPlacementComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The companion card: one companion's info and per-companion actions, opened by F on the companion.
 *
 * <p>The page only presents a {@link View} and reports clicks to its {@link Actions}; the page service checks
 * ownership and runs everything again on the world thread. Release and Cull ask for an inline confirmation first.
 * Info blocks reuse the companion window's binders, so the card shows exactly what a companion card in that window
 * shows.
 */
public final class TameworkCompanionCardPage extends InteractiveCustomUIPage<TameworkCompanionCardPage.EventData> {
    public static final String UI_PATH = "TameworkCompanionCard.ui";
    private static final String KEY_ACTION = "Action";
    /** Appearance traits the card lists, in two columns. */
    public static final int APPEARANCE_SLOTS = 8;
    private static final int FOOD_SLOTS = 9;
    /** Horizontal distance between food slots. */
    private static final int FOOD_SLOT_STEP = 45;
    private static final String KEY_GROUPS = "@CompanionGroups";
    /** The card's portrait starts at the identity block's top, 33 px higher than on the linked card. */
    private static final int LEVEL_TOP = LinkedNpcPanelProgressionBinder.LEVEL_TOP - 33;
    private static final String CARD = "#CompanionCard";
    private static final int MODE_SLOTS = 6;
    private static final int ACTION_SLOTS = 12;
    private static final int SLOTS_PER_ROW = 6;
    private static final String GLYPH_COLOR = "#f9edd5";
    private static final String GLYPH_MUTED = "#f9edd559";

    private final UUID npcUuid;
    private final Actions actions;
    private View view;
    @Nullable
    private Confirm pendingConfirm;
    private boolean closed;
    /** Whether the inspect camera is locked on, which shows the rotate buttons. */
    private boolean cameraControls;
    /** Whether the card opens in its camera layout (see {@link #useCameraLayout}). */
    private boolean cameraLayout;
    // Read once when the card opens; a change in Your settings applies the next time it opens.
    private CompanionHudPlacement placement = CompanionHudPlacement.CARD_DEFAULT;
    // What the group picker was last given. Re-sending Entries or SelectedValues resets an open native popup, so a
    // refresh sends them only when they changed.
    @Nullable
    private List<LinkedNpcEntry.GroupMembership> sentGroupOptions;
    @Nullable
    private List<String> sentGroupSelection;

    /**
     * What the page shows. Built by the page service on the world thread for one viewer.
     *
     * @param canManage      the viewer owns the companion: modes and {@code Card} entries are offered
     * @param genericActions the companion also accepts generic companion actions (it is not bonded): toggles,
     *                       homes, groups, talents, rename, release and cull are offered
     * @param groupOptions   every group the viewer has defined; the entry's {@code groups()} are the selected ones.
     *                       Empty hides the group picker
     */
    public record View(@Nonnull LinkedNpcEntry entry,
                       @Nonnull CompanionCardInteractions interactions,
                       boolean canManage,
                       boolean genericActions,
                       @Nullable String ownerName,
                       @Nonnull CardRenameAvailability rename,
                       @Nonnull List<LinkedNpcEntry.GroupMembership> groupOptions,
                       boolean closeAfterCommand,
                       @Nonnull List<CommandTargetHudViewModel.FoodRow> foods,
                       @Nonnull List<CommandTargetHudViewModel.AttachmentRow> appearance) {
    }

    /** Clicks reported to the page service. Every callback runs on the world thread. */
    public interface Actions {
        void mode(int stepIndex);

        void entry(int entryIndex, @Nonnull CompanionCardInteractions.Kind kind);

        void toggleBreeding();

        void toggleFlight();

        void toggleShoulder();

        void setHome();

        void returnHome();

        /** Replaces the companion's group memberships with {@code groupIds} (possibly empty). */
        void assignGroups(@Nonnull List<String> groupIds);

        void talents();

        void rename();

        void release();

        void cull();

        /** Flips the viewer's choice of whether a command closes the panel. */
        void toggleCloseAfterCommand();

        /** Turns the inspect camera one step around the companion: -1 left, +1 right. */
        void rotateCamera(int direction);

        /** The card closed for any reason. */
        void closed();
    }

    public enum Confirm { RELEASE, CULL }

    public TameworkCompanionCardPage(@Nonnull PlayerRef playerRef, @Nonnull UUID npcUuid, @Nonnull View view,
                                     @Nonnull Actions actions) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventData.CODEC);
        this.npcUuid = npcUuid;
        this.view = view;
        this.actions = actions;
    }

    @Nonnull
    public UUID npcUuid() {
        return npcUuid;
    }

    @Nonnull
    public View view() {
        return view;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        commands.append(UI_PATH);
        placement = TameworkCompanionCardPlacementComponent.read(ref, store);
        if (cameraLayout && placement.equals(CompanionHudPlacement.CARD_DEFAULT)) {
            placement = CompanionHudPlacement.CARD_CAMERA_DEFAULT;
        }
        sentGroupOptions = null;
        sentGroupSelection = null;
        render(commands, events);
    }

    /** Shows a new view, keeping an open confirmation only while its action is still offered. */
    public void show(@Nonnull View next) {
        if (closed) {
            return;
        }
        view = next;
        if (!next.canManage()) {
            pendingConfirm = null;
        }
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        render(commands, events);
        sendUpdate(commands, events, false);
    }

    /**
     * Opens the card at the screen edge when the inspect camera will frame the companion in the centre, unless the
     * viewer has placed the card themselves. Call before the page opens.
     */
    public void useCameraLayout(boolean camera) {
        cameraLayout = camera;
    }

    /** Shows the rotate buttons once the inspect camera has locked onto the companion. */
    public void showCameraControls() {
        cameraControls = true;
        if (closed) {
            return;
        }
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        bindCameraControls(commands, events);
        sendUpdate(commands, events, false);
    }

    private void bindCameraControls(UICommandBuilder commands, UIEventBuilder events) {
        for (String side : new String[]{"Left", "Right"}) {
            commands.set(CARD + " #Rotate" + side + "Button.Visible", cameraControls);
            commands.set(CARD + " #Rotate" + side + "Glyph.Visible", cameraControls);
        }
        if (cameraControls) {
            bind(events, CARD + " #RotateLeftButton", "rotate:-1");
            bind(events, CARD + " #RotateRightButton", "rotate:1");
        }
    }

    /** Closes the card from the server, for example after the player mounted the companion. */
    public void closeCard() {
        if (!closed) {
            close();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        markClosed();
    }

    private void markClosed() {
        if (!closed) {
            closed = true;
            actions.closed();
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull EventData data) {
        String action = data.action == null ? "" : data.action.trim();
        if (closed || action.isEmpty()) {
            return;
        }
        if (action.equals("close")) {
            close();
            markClosed();
            return;
        }
        if (action.equals("groups")) {
            // Not a confirmable action, so it neither answers nor cancels an open confirmation.
            if (generic() && !view.groupOptions().isEmpty()) {
                List<String> selected = data.groups == null ? List.of() : List.of(data.groups);
                // The client already shows this selection; do not send it back.
                sentGroupSelection = selected;
                actions.assignGroups(selected);
            }
            return;
        }
        if (action.equals("rotate:-1") || action.equals("rotate:1")) {
            // Moves only the camera: no confirmation is answered or cancelled, and the panel stays open.
            if (cameraControls) {
                actions.rotateCamera(action.equals("rotate:1") ? 1 : -1);
            }
            return;
        }
        if (action.equals("closeAfter")) {
            // A preference, not a confirmable action: it neither answers nor cancels an open confirmation.
            if (view.canManage()) {
                actions.toggleCloseAfterCommand();
            }
            return;
        }
        if (pendingConfirm != null) {
            handleConfirm(action);
            return;
        }
        if (action.startsWith("mode:")) {
            Integer index = parseIndex(action, "mode:");
            if (index != null && view.canManage() && view.interactions().modeAllowed()) {
                actions.mode(index);
                closeAfterCommand();
            }
            return;
        }
        if (action.startsWith("entry:")) {
            Integer index = parseIndex(action, "entry:");
            CompanionCardInteractions.Action entry = index == null ? null : findEntry(index);
            if (entry != null && view.canManage() && entry.status() == CompanionCardInteractions.Status.READY) {
                actions.entry(index, entry.kind());
                closeAfterCommand();
            }
            return;
        }
        switch (action) {
            case "setHome" -> { if (generic()) { actions.setHome(); closeAfterCommand(); } }
            case "returnHome" -> { if (generic()) { actions.returnHome(); closeAfterCommand(); } }
            case "breeding" -> { if (generic()) { actions.toggleBreeding(); closeAfterCommand(); } }
            case "flight" -> { if (generic()) { actions.toggleFlight(); closeAfterCommand(); } }
            case "shoulder" -> { if (generic()) { actions.toggleShoulder(); closeAfterCommand(); } }
            case "talents" -> { if (generic()) actions.talents(); }
            case "rename" -> {
                if (generic() && view.rename() == CardRenameAvailability.AVAILABLE) actions.rename();
            }
            case "release" -> askConfirm(Confirm.RELEASE);
            case "cull" -> askConfirm(Confirm.CULL);
            default -> {
            }
        }
    }

    /**
     * Closes the panel after a command (a mode or any button in the Actions section) when the viewer chose so.
     * Talents and rename open their own page; groups, the footer switch and removal keep the panel open.
     */
    private void closeAfterCommand() {
        if (view.closeAfterCommand() && !closed) {
            close();
            markClosed();
        }
    }

    private boolean generic() {
        return view.canManage() && view.genericActions();
    }

    private void askConfirm(Confirm confirm) {
        if (!generic() || (confirm == Confirm.CULL && !canCull()) || (confirm == Confirm.RELEASE && !canRelease())) {
            return;
        }
        pendingConfirm = confirm;
        show(view);
    }

    private void handleConfirm(String action) {
        Confirm confirm = pendingConfirm;
        pendingConfirm = null;
        if ("confirm".equals(action) && generic()) {
            if (confirm == Confirm.RELEASE && canRelease()) {
                actions.release();
                return;
            }
            if (confirm == Confirm.CULL && canCull()) {
                actions.cull();
                return;
            }
        }
        show(view);
    }

    @Nullable
    private CompanionCardInteractions.Action findEntry(int index) {
        for (CompanionCardInteractions.Action entry : view.interactions().actions()) {
            if (entry.entryIndex() == index) {
                return entry;
            }
        }
        return null;
    }

    private boolean canRelease() {
        LinkedNpcEntry entry = view.entry();
        return !entry.captured() && !entry.inCoop();
    }

    private boolean canCull() {
        LinkedNpcEntry entry = view.entry();
        return canRelease() && entry.loaded() && !entry.dead() && !entry.lost();
    }

    private void render(UICommandBuilder commands, UIEventBuilder events) {
        String language = playerRef.getLanguage();
        LinkedNpcEntry entry = view.entry();
        boolean manage = view.canManage();
        boolean generic = generic();
        bind(events, "#CloseButton", "close");
        bindCameraControls(commands, events);

        commands.set(CARD + " #Name.Text", entry.displayName());
        commands.set(CARD + " #RoleSubtitle.Text", entry.roleSubtitle());
        commands.set(CARD + " #GenderMaleIcon.Visible", entry.isMale());
        commands.set(CARD + " #GenderFemaleIcon.Visible", entry.isFemale());
        boolean renameVisible = generic && view.rename() != CardRenameAvailability.OFF
                && view.rename() != CardRenameAvailability.NOT_ALLOWED;
        boolean renameReady = view.rename() == CardRenameAvailability.AVAILABLE;
        commands.set(CARD + " #RenameButton.Visible", renameVisible);
        commands.set(CARD + " #RenameGlyph.Visible", renameVisible);
        commands.set(CARD + " #RenameButton.Disabled", !renameReady);
        commands.setObject(CARD + " #RenameGlyph.Background",
                glyph("Tamework/PanelActions/Rename_Glyph_Default.png", renameReady ? "#bfcdd5" : "#bfcdd559"));
        commands.set(CARD + " #RenameButton.TooltipText", LocalizedText.resolve(language, renameReady
                ? "tamework.ui.companionCard.rename" : "tamework.ui.companionCard.rename.needsItem"));
        if (renameVisible && renameReady) {
            bind(events, CARD + " #RenameButton", "rename");
        }

        bindIdentity(commands, events, entry, language, manage, generic);
        int top = 200;
        top = bindModes(commands, events, language, manage, top);
        boolean cooldowns = LinkedNpcPanelCardBinder.resolveLifecycleDisplay(entry, language).visible()
                || entry.hasKnownCooldowns();
        commands.set(CARD + " #CooldownRow.Visible", cooldowns);
        if (cooldowns) {
            commands.setObject(CARD + " #CooldownRow.Anchor", anchor(top, 14, 412, 37));
            LinkedNpcPanelCardBinder.bindLifecycle(commands, CARD, entry, language);
            top += 47;
        }
        top = bindActions(commands, events, entry, language, manage, generic, top);
        top = bindFood(commands, language, top);
        top = bindAppearance(commands, language, top);
        bindFooter(commands, events, entry, language, manage, generic, top);
        int height = top + 44 + 12;
        commands.setObject("#CompanionCardSlot.Anchor", placement.laneAnchor(CompanionHudPlacement.CARD_WIDTH));
        commands.setObject(CARD + ".Anchor", placement.plateAnchor(height, CompanionHudPlacement.CARD_WIDTH));
    }

    /** Food the companion eats, with its happiness value; "+N" stands for foods past the last slot. */
    private int bindFood(UICommandBuilder commands, String language, int top) {
        List<CommandTargetHudViewModel.FoodRow> foods = view.foods().stream()
                .filter(food -> food.itemId() != null && !food.itemId().isBlank())
                .toList();
        boolean visible = !foods.isEmpty();
        commands.set(CARD + " #FoodSection.Visible", visible);
        if (!visible) {
            return top;
        }
        commands.setObject(CARD + " #FoodSection.Anchor", anchor(top, 14, 412, 66));
        int shown = foods.size() > FOOD_SLOTS ? FOOD_SLOTS - 1 : foods.size();
        for (int slot = 0; slot < FOOD_SLOTS; slot++) {
            String selector = CARD + " #Food" + slot;
            boolean slotVisible = slot < shown;
            commands.set(selector + ".Visible", slotVisible);
            if (!slotVisible) {
                continue;
            }
            CommandTargetHudViewModel.FoodRow food = foods.get(slot);
            CommandTargetHudBinder.bindFoodValue(commands, selector, food.happinessDelta());
            CommandTargetHudBinder.bindFoodGrid(commands, selector + " #FoodGrid", food);
            commands.set(selector + " #FoodTooltip.TooltipText", CommandTargetHudBinder.foodTooltip(food, language));
        }
        int more = foods.size() - shown;
        commands.set(CARD + " #FoodMore.Visible", more > 0);
        if (more > 0) {
            commands.set(CARD + " #FoodMore.Text", "+" + more);
            commands.setObject(CARD + " #FoodMore.Anchor", anchor(30, shown * FOOD_SLOT_STEP, 40, 24));
        }
        return top + 74;
    }

    /** Appearance traits in two columns: the trait set's name, then the companion's value. */
    private int bindAppearance(UICommandBuilder commands, String language, int top) {
        List<CommandTargetHudViewModel.AttachmentRow> rows = view.appearance();
        boolean visible = !rows.isEmpty();
        commands.set(CARD + " #AppearanceSection.Visible", visible);
        if (!visible) {
            return top;
        }
        int lines = (Math.min(rows.size(), APPEARANCE_SLOTS) + 1) / 2;
        int height = 20 + lines * 20;
        commands.setObject(CARD + " #AppearanceSection.Anchor", anchor(top, 14, 412, height));
        for (int slot = 0; slot < APPEARANCE_SLOTS; slot++) {
            String selector = CARD + " #Appearance" + slot;
            boolean slotVisible = slot < rows.size();
            commands.set(selector + ".Visible", slotVisible);
            if (!slotVisible) {
                continue;
            }
            CommandTargetHudViewModel.AttachmentRow row = rows.get(slot);
            commands.set(selector + " #Name.Text",
                    LocalizedText.resolveConfigValue(language, row.setLabel(), row.setLabel()));
            commands.set(selector + " #Value.Text",
                    LocalizedText.resolveConfigValue(language, row.valueLabel(), row.valueLabel()));
        }
        return top + height + 8;
    }

    private void bindIdentity(UICommandBuilder commands, UIEventBuilder events, LinkedNpcEntry entry,
                              String language, boolean manage, boolean generic) {
        LinkedNpcPanelPortraitBinder.bind(commands, CARD, entry);
        commands.setObject(CARD + " #PortraitFrame.Background", UiIconStyle.forTexture(
                "Tamework/Slate/Portrait_" + capitalize(LinkedNpcPanelCardBinder.slateState(entry)) + ".png"));
        LinkedNpcPanelVitalsBinder.bind(commands, CARD, entry, language);
        LinkedNpcTraitIndicatorBinder.bind(commands, CARD, entry.traitIndicators());
        String ring = CARD + " #XpProgressRing";
        LinkedNpcPanelProgressionBinder.bindXpProgressRing(commands, ring, ring + " #XpLevelText",
                ring + " #XpTooltip", entry.futureStatA(), LEVEL_TOP);
        boolean talents = generic && entry.isTalentsActionVisible() && entry.isTalentsActionEnabled()
                && entry.futureStatA() != null;
        commands.set(ring + " #XpTooltip.Disabled", !talents);
        commands.set(ring + " #TalentsArrow.Visible", talents);
        String points = CARD + " #TalentPointAction";
        boolean showPoints = generic && entry.isTalentsActionVisible()
                && LinkedNpcPanelProgressionBinder.availableTalentPoints(entry.futureStatB()) > 0;
        commands.set(points + " #TalentPointButton.Disabled", !(showPoints && entry.isTalentsActionEnabled()));
        LinkedNpcPanelProgressionBinder.bindTalentPointIndicator(commands, points, points + " #TalentPointCount",
                points + " #TalentPointCountShadow", entry.futureStatB(), showPoints);
        if (talents) {
            bind(events, ring + " #XpTooltip", "talents");
        }
        if (showPoints && entry.isTalentsActionEnabled()) {
            bind(events, points + " #TalentPointButton", "talents");
        }
        boolean showOwner = !manage && view.ownerName() != null && !view.ownerName().isBlank();
        commands.set(CARD + " #OwnerLine.Visible", showOwner);
        commands.set(CARD + " #OwnerLine.Text", showOwner
                ? LocalizedText.format(language, "tamework.ui.companionCard.owner", view.ownerName()) : "");
        bindGroups(commands, events, entry, language, generic);
    }

    /** The companions window's multi-select group picker, for the viewer's own groups. */
    private void bindGroups(UICommandBuilder commands, UIEventBuilder events, LinkedNpcEntry entry,
                            String language, boolean generic) {
        String selector = CARD + " #GroupSelector";
        List<LinkedNpcEntry.GroupMembership> options = view.groupOptions();
        boolean visible = generic && !options.isEmpty();
        commands.set(selector + ".Visible", visible);
        commands.set(selector + "Label.Visible", visible);
        commands.set(selector + "Marker.Visible", visible);
        if (!visible) {
            return;
        }
        if (!options.equals(sentGroupOptions)) {
            commands.set(selector + ".Entries", options.stream()
                    .map(group -> new DropdownEntryInfo(LocalizableString.fromString(group.name()), group.id()))
                    .toList());
            sentGroupOptions = options;
            sentGroupSelection = null;
        }
        List<String> selected = entry.groupIds();
        if (!selected.equals(sentGroupSelection)) {
            // Literal wrappers send the IDs as plain strings without translating them.
            commands.set(selector + ".SelectedValues", selected.stream().map(LocalizableString::fromString).toList());
            sentGroupSelection = selected;
        }
        List<LinkedNpcEntry.GroupMembership> groups = entry.groups();
        commands.set(selector + "Label.Text", groups.isEmpty()
                ? LocalizedText.resolve(language, "tamework.ui.companions.noGroups")
                : groups.size() == 1 ? groups.getFirst().name()
                : LocalizedText.format(language, "tamework.ui.companions.groupSummary",
                        groups.getFirst().name(), groups.size() - 1));
        commands.set(selector + ".TooltipText", groups.isEmpty()
                ? LocalizedText.resolve(language, "tamework.ui.companionCard.groups") : entry.groupName());
        LinkedNpcPanelGroupTabBinder.bind(commands, selector, entry);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                com.hypixel.hytale.server.core.ui.builder.EventData.of(KEY_ACTION, "groups")
                        .append(KEY_GROUPS, selector + ".SelectedValues"), false);
    }

    private int bindModes(UICommandBuilder commands, UIEventBuilder events, String language, boolean manage,
                          int top) {
        List<CompanionCardInteractions.Mode> modes = view.interactions().modes();
        boolean visible = !modes.isEmpty();
        commands.set(CARD + " #ModeSection.Visible", visible);
        if (!visible) {
            return top;
        }
        commands.setObject(CARD + " #ModeSection.Anchor", anchor(top, 14, 412, 82));
        boolean enabled = manage && view.interactions().modeAllowed();
        String current = "";
        for (int slot = 0; slot < MODE_SLOTS; slot++) {
            String prefix = CARD + " #Mode" + slot;
            boolean shown = slot < modes.size();
            setSlotVisible(commands, prefix, shown);
            if (!shown) {
                continue;
            }
            CompanionCardInteractions.Mode mode = modes.get(slot);
            if (mode.current()) {
                current = mode.label();
            }
            commands.set(prefix + "Button.Style", Value.ref("TameworkSlateStyles.ui",
                    mode.current() ? "ActionOn" : enabled ? "Action" : "ActionDisabled"));
            commands.set(prefix + "Button.Disabled", !enabled || mode.current());
            commands.set(prefix + "Button.TooltipText", mode.label());
            commands.setObject(prefix + "Glyph.Background",
                    glyph(mode.icon(), enabled || mode.current() ? GLYPH_COLOR : GLYPH_MUTED));
            commands.set(prefix + "Caption.Text", mode.label());
            commands.set(prefix + "Caption.Style", Value.ref(UI_PATH,
                    mode.current() ? "CaptionCurrent" : enabled ? "Caption" : "CaptionMuted"));
            if (enabled && !mode.current()) {
                bind(events, prefix + "Button", "mode:" + mode.stepIndex());
            }
        }
        commands.set(CARD + " #ModeCurrent.Text", current);
        return top + 92;
    }

    private int bindActions(UICommandBuilder commands, UIEventBuilder events, LinkedNpcEntry entry,
                            String language, boolean manage, boolean generic, int top) {
        List<Slot> slots = new ArrayList<>();
        if (generic && entry.loaded()) {
            if (entry.shoulderRideAvailable()) {
                boolean on = entry.shoulderRideMounted();
                slots.add(new Slot(on ? "Tamework/PanelActions/ShoulderOn_Glyph_Default.png"
                        : "Tamework/PanelActions/ShoulderOff_Glyph_Default.png",
                        LocalizedText.resolve(language, "tamework.ui.linkedPanel.action." + (on ? "shoulderOff" : "shoulderOn")),
                        LocalizedText.resolve(language, on ? "tamework.ui.linkedPanel.bonded.shoulder.down.tooltip"
                                : "tamework.ui.linkedPanel.bonded.shoulder.toMe.tooltip"),
                        on, true, "shoulder"));
            }
            if (entry.flightToggleAvailable()) {
                boolean airborne = entry.flightToggleAirborne();
                slots.add(new Slot(airborne ? "Tamework/PanelActions/FlightAirborne_Glyph_Default.png"
                        : "Tamework/PanelActions/FlightGrounded_Glyph_Default.png",
                        LocalizedText.resolve(language, "tamework.ui.linkedPanel.action." + (airborne ? "flightAirborne" : "flightGrounded")),
                        LocalizedText.resolve(language, airborne ? "tamework.ui.linkedPanel.bonded.flight.switchToGround"
                                : "tamework.ui.linkedPanel.bonded.flight.switchToFlight"),
                        airborne, true, "flight"));
            }
            if (entry.breedingAvailable()) {
                boolean on = entry.breedingEnabled();
                slots.add(new Slot(on ? "Tamework/PanelActions/BreedingOn_Glyph_Default.png"
                        : "Tamework/PanelActions/BreedingOff_Glyph_Default.png",
                        LocalizedText.resolve(language, "tamework.ui.linkedPanel.action." + (on ? "breedingOn" : "breedingOff")),
                        LocalizedText.resolve(language, on ? "tamework.ui.linkedPanel.card.tooltip.breedingEnabled"
                                : "tamework.ui.linkedPanel.card.tooltip.breedingDisabled"),
                        on, true, "breeding"));
            }
            if (!entry.dead() && !entry.captured() && !entry.inCoop() && !entry.lost()) {
                slots.add(new Slot("Tamework/PanelActions/SetHome_Glyph_Default.png",
                        LocalizedText.resolve(language, "tamework.ui.linkedPanel.action.setHome"),
                        LocalizedText.resolve(language, "tamework.ui.linkedPanel.card.tooltip.setHome"),
                        false, true, "setHome"));
                if (entry.hasHome()) {
                    slots.add(new Slot("Tamework/PanelActions/ReturnHome_Glyph_Default.png",
                            LocalizedText.resolve(language, "tamework.ui.linkedPanel.action.returnHome"),
                            LocalizedText.resolve(language, "tamework.ui.linkedPanel.card.tooltip.returnHome"),
                            false, true, "returnHome"));
                }
            }
        }
        if (manage) {
            for (CompanionCardInteractions.Action action : view.interactions().actions()) {
                boolean ready = action.status() == CompanionCardInteractions.Status.READY;
                String tooltip = action.status() == CompanionCardInteractions.Status.COOLDOWN
                                ? LocalizedText.resolve(language, "tamework.ui.companionCard.cooldown")
                                : action.status() == CompanionCardInteractions.Status.UNAVAILABLE
                                        ? LocalizedText.resolve(language, "tamework.ui.companionCard.unavailable")
                                        : action.label();
                slots.add(new Slot(action.icon(), action.label(), tooltip, false, ready,
                        "entry:" + action.entryIndex()));
            }
        }
        int shown = Math.min(slots.size(), ACTION_SLOTS);
        commands.set(CARD + " #ActionSection.Visible", shown > 0);
        for (int slot = 0; slot < ACTION_SLOTS; slot++) {
            String prefix = CARD + " #Action" + slot;
            boolean visible = slot < shown;
            setSlotVisible(commands, prefix, visible);
            if (!visible) {
                continue;
            }
            Slot value = slots.get(slot);
            commands.set(prefix + "Button.Style", Value.ref("TameworkSlateStyles.ui",
                    !value.enabled() ? "ActionDisabled" : value.on() ? "ActionOn" : "Action"));
            commands.set(prefix + "Button.Disabled", !value.enabled());
            commands.set(prefix + "Button.TooltipText", value.tooltip());
            commands.setObject(prefix + "Glyph.Background",
                    glyph(value.icon(), value.enabled() ? GLYPH_COLOR : GLYPH_MUTED));
            commands.set(prefix + "Caption.Text", value.caption());
            commands.set(prefix + "Caption.Style", Value.ref(UI_PATH, value.enabled() ? "Caption" : "CaptionMuted"));
            if (value.enabled()) {
                bind(events, prefix + "Button", value.event());
            }
        }
        if (shown == 0) {
            return top;
        }
        int rows = (shown + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW;
        int height = 20 + rows * 68;
        commands.setObject(CARD + " #ActionSection.Anchor", anchor(top, 14, 412, height));
        return top + height + 6;
    }

    private void bindFooter(UICommandBuilder commands, UIEventBuilder events, LinkedNpcEntry entry,
                            String language, boolean manage, boolean generic, int top) {
        String footer = CARD + " #Footer";
        commands.setObject(footer + ".Anchor", anchor(top, 14, 412, 44));
        Confirm confirm = generic ? pendingConfirm : null;
        boolean confirming = confirm != null;
        commands.set(footer + " #ReadOnlyNote.Visible", !manage);
        boolean closeSwitch = manage && !confirming;
        commands.set(footer + " #CloseAfterCommandSwitch.Visible", closeSwitch);
        commands.set(footer + " #CloseAfterCommandLabel.Visible", closeSwitch);
        if (closeSwitch) {
            commands.set(footer + " #CloseAfterCommandSwitch.Style", Value.ref("TameworkSlateStyles.ui",
                    view.closeAfterCommand() ? "SwitchOn" : "SwitchOff"));
            bind(events, footer + " #CloseAfterCommandSwitch", "closeAfter");
        }
        boolean release = generic && !confirming && canRelease();
        boolean cull = generic && !confirming && canCull();
        commands.set(footer + " #ReleaseButton.Visible", release);
        commands.set(footer + " #CullButton.Visible", cull);
        if (release) {
            bind(events, footer + " #ReleaseButton", "release");
        }
        if (cull) {
            bind(events, footer + " #CullButton", "cull");
        }
        commands.set(footer + " #ConfirmText.Visible", confirming);
        commands.set(footer + " #ConfirmCancelButton.Visible", confirming);
        commands.set(footer + " #ConfirmButton.Visible", confirming);
        if (confirming) {
            commands.set(footer + " #ConfirmText.Text", LocalizedText.format(language, confirm == Confirm.RELEASE
                    ? "tamework.ui.companionCard.confirmRelease" : "tamework.ui.companionCard.confirmCull",
                    entry.displayName()));
            commands.set(footer + " #ConfirmButton.Text", LocalizedText.resolve(language, confirm == Confirm.RELEASE
                    ? "tamework.ui.linkedPanel.card.button.release" : "tamework.ui.linkedPanel.action.cull"));
            bind(events, footer + " #ConfirmButton", "confirm");
            bind(events, footer + " #ConfirmCancelButton", "cancel");
        }
    }

    private static void setSlotVisible(UICommandBuilder commands, String prefix, boolean visible) {
        commands.set(prefix + "Button.Visible", visible);
        commands.set(prefix + "Glyph.Visible", visible);
        commands.set(prefix + "Caption.Visible", visible);
    }

    private static void bind(UIEventBuilder events, String selector, String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                com.hypixel.hytale.server.core.ui.builder.EventData.of(KEY_ACTION, action), false);
    }

    private static PatchStyle glyph(String texture, String color) {
        return new PatchStyle(Value.of(texture)).setColor(Value.of(color));
    }

    private static Anchor anchor(int top, int left, int width, int height) {
        return LinkedNpcPanelCardBinder.fixedAnchor(top, left, width, height);
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    @Nullable
    private static Integer parseIndex(String action, String prefix) {
        try {
            int value = Integer.parseInt(action.substring(prefix.length()));
            return value >= 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record Slot(String icon, String caption, String tooltip, boolean on, boolean enabled, String event) {
    }

    /** Event payload: one action string per click, plus the picked group IDs for a group change. */
    public static final class EventData {
        public static final BuilderCodec<EventData> CODEC = BuilderCodec.builder(EventData.class, EventData::new)
                .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING),
                        (data, value) -> data.action = value,
                        data -> data.action)
                .add()
                .append(new KeyedCodec<>(KEY_GROUPS, Codec.STRING_ARRAY),
                        (data, value) -> data.groups = value,
                        data -> data.groups)
                .add()
                .build();

        private String action;
        private String[] groups;
    }
}
