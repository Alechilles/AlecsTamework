package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignal;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignalSource;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tells an owner's open generic panels that their companion list changed after the click that
 * started the change, for example when an asynchronous restore completes. Safe from any thread:
 * a signal only asks the page's refresh coordinator for a refresh, which runs on the page's world
 * thread. A page subscribes while it is open and its refresh lifecycle closes the subscription.
 */
final class CommandPanelOwnerSignals {
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /** Signals for {@code owner}'s pages; none when the owner is unknown. */
    @Nonnull
    LinkedPanelRefreshSignalSource forOwner(@Nullable UUID owner) {
        if (owner == null) {
            return LinkedPanelRefreshSignalSource.none();
        }
        return listener -> {
            Subscription subscription = new Subscription(owner, listener);
            subscriptions.add(subscription);
            return () -> subscriptions.remove(subscription);
        };
    }

    /** Asks every open page of {@code owner} to rebuild its card list now. */
    void changed(@Nullable UUID owner) {
        if (owner == null) {
            return;
        }
        for (Subscription subscription : subscriptions) {
            if (owner.equals(subscription.owner())) {
                try {
                    subscription.listener().accept(
                            new LinkedPanelRefreshSignal(LinkedPanelRefreshSignal.Kind.IMMEDIATE));
                } catch (RuntimeException ignored) {
                    // A page that is closing must not stop the other pages from refreshing.
                }
            }
        }
    }

    private record Subscription(UUID owner, Consumer<LinkedPanelRefreshSignal> listener) { }
}
