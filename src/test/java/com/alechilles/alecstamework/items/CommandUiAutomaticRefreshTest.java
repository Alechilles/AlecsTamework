package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshCoordinator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Custom pages must receive host-owned live presentation refreshes. */
class CommandUiAutomaticRefreshTest {
    @Test
    void progressionWakeRequestsRefreshAndCloseStopsLaterWakes() {
        List<Runnable> scheduled = new ArrayList<>();
        AtomicInteger refreshes = new AtomicInteger();
        CommandUiSnapshot snapshot = new CommandUiSnapshot(
                UUID.randomUUID(), 1L, 1L, null, List.of(), List.of(),
                new CommandUiPanelState("linked"));
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                snapshot.sessionId(), snapshot, new CommandUiActionGateway(),
                CommandUiWorldDispatcher.direct(),
                CommandUiSessionImpl.Mode.MIXED,
                refreshes::incrementAndGet, null, null);
        CommandUiAutomaticRefresh lifecycle = new CommandUiAutomaticRefresh(
                session, () -> 0L,
                (delay, callback) -> scheduled.add(callback));

        lifecycle.start();
        scheduled.removeFirst().run();
        lifecycle.close();
        List.copyOf(scheduled).forEach(Runnable::run);

        assertEquals(1, refreshes.get());
        session.close();
    }
}
