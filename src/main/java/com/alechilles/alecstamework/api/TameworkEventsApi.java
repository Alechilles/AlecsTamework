package com.alechilles.alecstamework.api;

import java.util.function.Consumer;

public interface TameworkEventsApi {
    /**
     * Subscribes to a released compatibility event. Prefer Activity API V2 for new integrations.
     */
    <E extends TameworkEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> listener);
}

