package com.alechilles.alecstamework.api;

import java.util.function.Consumer;

public interface TameworkEventsApi {
    <E extends TameworkEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> listener);
}

