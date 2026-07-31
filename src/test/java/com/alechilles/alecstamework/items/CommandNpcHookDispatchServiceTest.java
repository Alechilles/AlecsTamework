package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.lang.reflect.Field;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Regression coverage for the common command and panel hook payload. */
class CommandNpcHookDispatchServiceTest {
    @Test
    void createsOneShotHookWithExactCommandMetadata() throws Exception {
        UUID playerId = UUID.fromString("ea939ad4-bc66-4e72-bf64-31fe7f876395");
        Player player = player(playerId, "test-owner");
        Vector3d target = new Vector3d(12.5, 64.0, -9.25);

        TameworkHookComponent component = CommandNpcHookDispatchService
                .createComponent("HyDragon.Command.ToggleAirborneMode", player,
                        "test:dragon_horn", 123456789L, target);

        assertEquals("HyDragon.Command.ToggleAirborneMode", component.getHookId());
        assertEquals(playerId, component.getPlayerId());
        assertEquals("test-owner", component.getPlayerName());
        assertEquals("test:dragon_horn", component.getHeldItemId());
        assertEquals(123456789L, component.getTimestampMs());
        assertTrue(component.isConsumeOnMatch());
        assertEquals(target, component.getTargetPosition());
    }

    @Test
    void createsHookWithoutTargetPositionWhenCommandHasNone() throws Exception {
        TameworkHookComponent component = CommandNpcHookDispatchService
                .createComponent("test.hook", player(UUID.randomUUID(), "owner"),
                        null, 4L, null);

        assertFalse(component.hasTargetPosition());
        assertNull(component.getTargetPosition());
    }

    @Test
    void rejectsBlankHookIdsAndMissingPlayers() throws Exception {
        Player player = player(UUID.randomUUID(), "owner");
        assertNull(CommandNpcHookDispatchService.createComponent(null, player,
                "item", 1L, null));
        assertNull(CommandNpcHookDispatchService.createComponent(" ", null,
                "item", 1L, null));
        assertNull(CommandNpcHookDispatchService.createComponent("hook", null,
                "item", 1L, null));
    }

    @Test
    void rejectsMissingOrInvalidNpcReferences() throws Exception {
        CommandNpcHookDispatchService service =
                new CommandNpcHookDispatchService();
        Player player = player(UUID.randomUUID(), "owner");

        assertFalse(service.dispatch("hook", player, "item", null, null,
                null));
    }

    private static Player player(UUID uuid, String username) throws Exception {
        Player player = (Player) unsafe().allocateInstance(Player.class);
        player.setLegacyUUID(uuid);
        PlayerRef ref = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
        setField(ref, PlayerRef.class, "uuid", uuid);
        setField(ref, PlayerRef.class, "username", username);
        setField(player, Player.class, "playerRef", ref);
        return player;
    }

    private static void setField(Object target, Class<?> type,
                                 String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
