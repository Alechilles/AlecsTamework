package com.alechilles.alecstamework.commands;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Admin and test commands name owners who may be offline (no universe runs here, so nobody is online). */
class TameworkCommandPlayersTest {
    @Test
    void offlineOwnerGivenByUuidIsAcceptedWithoutAName() {
        UUID owner = UUID.randomUUID();

        TameworkCommandPlayers.Named named = TameworkCommandPlayers.resolve(" " + owner + " ");

        assertEquals(owner, named.uuid());
        assertNull(named.name());
    }

    @Test
    void nameOfAPlayerWhoIsNotOnlineResolvesToNobody() {
        assertNull(TameworkCommandPlayers.resolve("SomeOfflinePlayer"));
        assertNull(TameworkCommandPlayers.resolve(" "));
    }
}
