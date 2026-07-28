package com.alechilles.alecstamework.config.bonded;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Regression coverage for the atomic roster/dependent-command read boundary. */
class BondedCompanionConfigGenerationConcurrencyTest {
    @Test
    void readerNeverObservesBondedCommandWithoutItsRoster() throws Exception {
        BondedCompanionRosterRegistry rosters =
                new BondedCompanionRosterRegistry();
        CommandItemRegistry commands = new CommandItemRegistry(rosters);
        BondedCompanionConfigReloadService reloads =
                new BondedCompanionConfigReloadService(rosters, commands);
        TwBondedCompanionRosterConfig roster = roster();
        TwCommandItemConfig command = command();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> observed = new AtomicReference<>();

        Thread reader = Thread.ofPlatform().start(() -> {
            await(start);
            while (running.get() && observed.get() == null) {
                BondedCompanionRosterRegistry.CoherentSnapshot generation =
                        rosters.coherentSnapshot();
                boolean rosterPresent = generation.rosters()
                        .byRosterId().containsKey("hydragon:dragons");
                boolean commandPresent = generation.commands()
                        .byItemId().containsKey("HyDragon_Dragon_Horn");
                if (rosterPresent != commandPresent) {
                    observed.compareAndSet(null, new AssertionError(
                            "mismatched bonded generation"
                    ));
                }
            }
        });
        start.countDown();
        try {
            for (int iteration = 0; iteration < 10_000; iteration++) {
                assertTrue(reloads.reload(
                        List.of(roster), List.of(command)
                ).applied());
                assertTrue(reloads.reload(List.of(), List.of()).applied());
            }
        } finally {
            running.set(false);
            reader.join();
        }

        assertNull(observed.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static TwBondedCompanionRosterConfig roster() throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "hydragon:dragons",
                                  "FamilyId": "hydragon:dragon",
                                  "AllowedRoles": ["Tamed_Dragon_Fire"]
                                }
                                """),
                        new ExtraInfo()
                );
        set(config, "id", "Roster");
        return config;
    }

    private static TwCommandItemConfig command() throws Exception {
        TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "ItemIds": ["HyDragon_Dragon_Horn"],
                          "RosterStorage": "BondedCompanions",
                          "BondedRosterId": "hydragon:dragons"
                        }
                        """),
                new ExtraInfo()
        );
        set(config, "id", "Command");
        return config;
    }

    private static void set(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
