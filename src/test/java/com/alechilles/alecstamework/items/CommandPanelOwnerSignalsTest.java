package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignal;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignalSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandPanelOwnerSignalsTest {
    @Test
    void aChangeRefreshesOnlyThatOwnersOpenPagesUntilTheyClose() throws Exception {
        CommandPanelOwnerSignals signals = new CommandPanelOwnerSignals();
        UUID owner = UUID.randomUUID();
        List<LinkedPanelRefreshSignal.Kind> received = new ArrayList<>();
        AutoCloseable page = signals.forOwner(owner, LinkedPanelRefreshSignalSource.none())
                .subscribe(signal -> received.add(signal.kind()));

        signals.changed(UUID.randomUUID());
        signals.changed(owner);
        assertEquals(List.of(LinkedPanelRefreshSignal.Kind.IMMEDIATE), received);

        page.close();
        signals.changed(owner);
        assertEquals(1, received.size());
    }
}
