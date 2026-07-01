package com.alechilles.alecstamework.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StoreScopedStateTest {
    @Test
    void returnsSameStateForSameStoreKey() {
        StoreScopedState<State> states = new StoreScopedState<>(State::new);
        Object store = new Object();

        State first = states.get(store);
        State second = states.get(store);

        assertSame(first, second);
        assertEquals(1, states.sizeForTests());
    }

    @Test
    void returnsDifferentStateForDifferentStoreKeys() {
        StoreScopedState<State> states = new StoreScopedState<>(State::new);

        State first = states.get(new Object());
        State second = states.get(new Object());

        assertNotSame(first, second);
        assertEquals(2, states.sizeForTests());
    }

    @Test
    void removesStateForStoreKey() {
        StoreScopedState<State> states = new StoreScopedState<>(State::new);
        Object store = new Object();
        State first = states.get(store);

        states.remove(store);
        State second = states.get(store);

        assertNotSame(first, second);
        assertEquals(1, states.sizeForTests());
    }

    @Test
    void rejectsNullStoreKey() {
        StoreScopedState<State> states = new StoreScopedState<>(State::new);

        assertThrows(NullPointerException.class, () -> states.get(null));
    }

    private static final class State {
    }
}
