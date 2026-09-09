package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedCompanionAbandonPresentationTest {
    /** Owned unlinked cards expose offscreen actions without pretending to be linked. */
    @Test void ownedUnloadedAnimalOffersRecallAndLocateWithoutLinkControls() {
        var owned = entry(false).withOwnedActions();
        var commands = render(owned, false);
        assertValue(commands, "RecallButton.Visible", "true");
        assertValue(commands, "LocateButton.Visible", "true");
        assertValue(commands, "SetHomeButton.Visible", "false");
        assertValue(render(entry(false), false), "RecallButton.Visible", "false");
        assertValue(render(entry(true).withOwnedActions(), false), "RecallButton.Visible", "false");
        assertValue(render(entry(true).withOwnedActions(), false), "LocateButton.Visible", "false");
    }

    @Test void ownedRevivalAndRecoveryRespectCooldownAndManagedAuthority() {
        assertValue(render(entry(false, true, false, 0).withOwnedActions(), false),
                "RespawnButton.Visible", "true");
        assertValue(render(entry(false, false, true, 0).withOwnedActions(), false),
                "RespawnButton.Visible", "true");
        assertValue(render(entry(false, true, false, 1000).withOwnedActions(), false),
                "RespawnButton.Visible", "false");
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(commands, new UIEventBuilder(), 0,
                entry(false, true, false, 0).withOwnedActions(), false, false,
                LinkedNpcPanelCardBindingFactory.create(true, false), "en-US",
                CommandPanelFeaturePresentation.readOnlyManaged());
        assertValue(commands, "RespawnButton.Visible", "false");
    }
    /** Off-screen unlinked animals must offer confirmed abandonment without live-only actions. */
    @Test
    void unloadedUnlinkedAnimalOffersAbandonAfterOpeningRemovalControls() {
        var entry = entry(false);
        var initial = render(entry, false);
        assertValue(initial, "RemoveButton.Visible", "true");
        assertValue(initial, "ReleaseButton.Visible", "false");
        var confirmation = render(entry, true);
        assertValue(confirmation, "ReleaseButton.Visible", "true");
        assertValue(confirmation, "ReleaseButton.Text", "Abandon");
        assertValue(confirmation, "CullButton.Visible", "false");
        assertValue(confirmation, "LinkButton.Visible", "false");
    }

    /** Capture storage must be released through its own lifecycle before abandonment. */
    @Test
    void capturedUnlinkedAnimalDoesNotOfferAbandon() {
        assertValue(render(entry(true), true), "ReleaseButton.Visible", "false");
    }

    /** Generic items must not release a companion governed by a managed roster. */
    @Test
    void managedAnimalDoesNotOfferGenericAbandon() {
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(commands, new UIEventBuilder(), 0, entry(false),
                false, true, LinkedNpcPanelCardBindingFactory.create(true, false), "en-US",
                CommandPanelFeaturePresentation.readOnlyManaged());
        assertValue(commands, "RemoveButton.Visible", "false");
        assertValue(commands, "ReleaseButton.Visible", "false");
        assertValue(commands, "RosterSummonButton.Visible", "false");
    }

    private static LinkedNpcEntry entry(boolean captured) {
        return entry(captured, false, false, 0L);
    }

    private static LinkedNpcEntry entry(boolean captured, boolean dead, boolean lost, long cooldown) {
        return new LinkedNpcEntry(UUID.randomUUID(), "Cow", 0, 0, 0, 0, 0, null,
                0, 0, 0, 0, false, false, dead, captured, false, lost, cooldown,
                null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, false, true,
                "Cow", "Cow", null, null, null, false, false, 0L, 0.0, false);
    }

    private static UICommandBuilder render(LinkedNpcEntry entry, boolean confirm) {
        UICommandBuilder commands = new UICommandBuilder();
        var snapshot = LinkedNpcEntrySnapshotMapper.build(java.util.List.of(entry))[0];
        LinkedNpcPanelCardBinder.bind(commands, new UIEventBuilder(), 0, snapshot,
                false, confirm, LinkedNpcPanelCardBindingFactory.create(true, false), "en-US");
        return commands;
    }

    private static void assertValue(UICommandBuilder commands, String suffix, String value) {
        String selector = "#TameworkLinkedPanelList[0] #" + suffix;
        assertTrue(Arrays.stream(commands.getCommands()).anyMatch(command ->
                selector.equals(command.selector) && command.data.contains(value)), selector);
    }
}
