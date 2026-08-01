package com.alechilles.alecstamework.ui;

/** Package-scoped construction dependency used by deterministic page tests. */
final class LinkedNpcPanelRefreshTestSeam {
    private static final ThreadLocal<LinkedNpcPanelPacketSender> PACKET_SENDER = new ThreadLocal<>();
    private static final ThreadLocal<LinkedNpcPanelDeferredNavigator> DEFERRED_NAVIGATOR = new ThreadLocal<>();
    private LinkedNpcPanelRefreshTestSeam() { }
    static LinkedNpcPanelPacketSender takePacketSender() {
        LinkedNpcPanelPacketSender sender = PACKET_SENDER.get();
        PACKET_SENDER.remove();
        return sender;
    }
    static AutoCloseable installPacketSender(LinkedNpcPanelPacketSender sender) {
        PACKET_SENDER.set(sender);
        return PACKET_SENDER::remove;
    }
    static LinkedNpcPanelDeferredNavigator takeDeferredNavigator() {
        LinkedNpcPanelDeferredNavigator navigator = DEFERRED_NAVIGATOR.get();
        DEFERRED_NAVIGATOR.remove();
        return navigator;
    }
    static AutoCloseable installDeferredNavigator(LinkedNpcPanelDeferredNavigator navigator) {
        DEFERRED_NAVIGATOR.set(navigator);
        return DEFERRED_NAVIGATOR::remove;
    }
}
