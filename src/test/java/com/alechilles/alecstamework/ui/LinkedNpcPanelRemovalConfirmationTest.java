package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Ensures destructive generic removal actions need the modal's final approval. */
class LinkedNpcPanelRemovalConfirmationTest {
    private static final UUID OWNER = UUID.fromString("a1000000-0000-0000-0000-000000000002");
    private static final UUID CARD = UUID.fromString("a2000000-0000-0000-0000-000000000002");
    private static final LinkedNpcEntry ENTRY = new LinkedNpcEntry(
            CARD, "Nimbus", 10, 10, 0, 0, null, 0, 0, 0, 0,
            true, true, false, false, false, false, 0L,
            new LinkedNpcTraitIndicator[0]
    );

    @Test
    void releaseNeedsModalConfirmationAndCancelKeepsRemovalMenuOpen() throws Exception {
        AtomicReference<List<LinkedNpcEntry>> entries = new AtomicReference<>(List.of(ENTRY));
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger culls = new AtomicInteger();
        TameworkCommandSelectionPage page = page(entries, releases, culls);
        build(page);

        event(page, "__removal_menu__:" + CARD);
        event(page, "__release__:" + CARD);
        assertEquals(0, releases.get());
        assertTrue(page.removalConfirmOverlay.isVisible());

        event(page, "__cull__:" + CARD);
        assertEquals(0, releases.get());
        assertEquals(0, culls.get());
        assertTrue(page.removalConfirmOverlay.isVisible());

        event(page, "__removal_confirm_cancel__");
        assertFalse(page.removalConfirmOverlay.isVisible());
        assertTrue(page.isPendingUnlink(CARD));

        event(page, "__removal_confirm__:1");
        assertEquals(0, releases.get());
        assertTrue(page.isPendingUnlink(CARD));

        event(page, "__release__:" + CARD);
        event(page, "__removal_confirm__:3");
        assertEquals(1, releases.get());
        assertFalse(page.isPendingUnlink(CARD));
    }

    @Test
    void staleConfirmationCannotApproveANewDialogOrRepeatAnAction() throws Exception {
        AtomicReference<List<LinkedNpcEntry>> entries = new AtomicReference<>(List.of(ENTRY));
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger culls = new AtomicInteger();
        TameworkCommandSelectionPage page = page(entries, releases, culls);
        build(page);

        event(page, "__removal_menu__:" + CARD);
        event(page, "__release__:" + CARD);
        String staleConfirm = "__removal_confirm__:" + page.removalConfirmOverlay.revision();
        event(page, "__removal_confirm_cancel__");
        event(page, "__cull__:" + CARD);
        String currentConfirm = "__removal_confirm__:" + page.removalConfirmOverlay.revision();

        event(page, staleConfirm);
        assertEquals(0, releases.get());
        assertEquals(0, culls.get());
        assertTrue(page.removalConfirmOverlay.isVisible());

        event(page, currentConfirm);
        event(page, currentConfirm);
        assertEquals(0, releases.get());
        assertEquals(1, culls.get());
    }

    @Test
    void confirmationRejectsAnInvalidatedTarget() throws Exception {
        AtomicReference<List<LinkedNpcEntry>> entries = new AtomicReference<>(List.of(ENTRY));
        AtomicInteger culls = new AtomicInteger();
        TameworkCommandSelectionPage page = page(entries, new AtomicInteger(), culls);
        build(page);

        event(page, "__removal_menu__:" + CARD);
        event(page, "__cull__:" + CARD);
        entries.set(List.of());

        new CommandSelectionLinkedPanelRuntime(page).refreshEntries();
        assertFalse(page.removalConfirmOverlay.isVisible());

        event(page, "__removal_confirm__:1");
        assertEquals(0, culls.get());
        assertFalse(page.removalConfirmOverlay.isVisible());
        assertFalse(page.isPendingUnlink(CARD));
    }

    private static TameworkCommandSelectionPage page(
            AtomicReference<List<LinkedNpcEntry>> entries,
            AtomicInteger releases,
            AtomicInteger culls
    ) throws Exception {
        try (AutoCloseable packetSenderScope = LinkedNpcPanelRefreshTestSeam.installPacketSender(
                (commands, events) -> { });
             AutoCloseable ignoredNavigator = LinkedNpcPanelRefreshTestSeam.installDeferredNavigator(
                     (player, action) -> { })) {
            PlayerRef player = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
            put(player, "uuid", OWNER);
            put(player, "username", "RemovalConfirmationTester");
            put(player, "language", "en-US");
            Consumer<UUID> noUuid = ignoredId -> { };
            Consumer<String> noString = ignoredValue -> { };
            BiConsumer<UUID, String> noGroup = (ignoredId, ignoredGroup) -> { };
            return new TameworkCommandSelectionPage(
                    player, legacyConfig(), null, true,
                    entries::get, entries::get, Map::of, () -> null,
                    () -> "LinkedMode", () -> false, () -> "16",
                    () -> "Default", () -> "None", () -> "", List::of,
                    () -> "", List::of, ignored -> true, true,
                    noUuid, noUuid, noUuid, noUuid,
                    ignoredId -> releases.incrementAndGet(),
                    ignoredId -> culls.incrementAndGet(),
                    noUuid,
                    (id, ref, store) -> { }, (id, ref, store) -> { },
                    (id, ref, store) -> { }, (id, ref, store) -> { },
                    (id, ref, store) -> { },
                    noUuid, noUuid, noUuid, noUuid, noUuid,
                    noString, ignored -> { }, () -> { }, () -> { }, () -> { },
                    noString, noString, noString, () -> { }, noString, noGroup,
                    noString, LinkedPanelRefreshSignalSource.none()
            );
        }
    }

    private static void build(TameworkCommandSelectionPage page) {
        page.build(null, new UICommandBuilder(), new UIEventBuilder(), null);
    }

    private static void event(TameworkCommandSelectionPage page, String command)
            throws Exception {
        CommandSelectionEventData data = new CommandSelectionEventData();
        Field field = CommandSelectionEventData.class.getDeclaredField("commandId");
        field.setAccessible(true);
        unsafe().putObject(data, unsafe().objectFieldOffset(field), command);
        page.handleDataEvent(null, null, data);
    }

    private static TwCommandItemConfig legacyConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("{\"CommandList\":[]}"),
                new com.hypixel.hytale.codec.ExtraInfo());
    }

    private static void put(Object target, String name, Object value) throws Exception {
        Field field = PlayerRef.class.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
