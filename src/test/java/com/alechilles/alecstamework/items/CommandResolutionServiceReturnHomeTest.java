package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandStep;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveSource;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MoveToPositionStep;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests return-home command resolution for linked-panel actions. */
class CommandResolutionServiceReturnHomeTest {

    @Test
    void usesBuiltInReturnHomeWhenConfigOmitsIt() throws Exception {
        CommandResolutionService service = new CommandResolutionService(null, 64.0);
        TwCommandItemConfig config = configWithCommands(commandWithId("Follow"));

        CommandEntry resolved = service.resolvePanelReturnHomeCommand(config, null);

        assertNotNull(resolved);
        assertEquals("ReturnHome", resolved.getId());
        assertEquals("Return Home", resolved.getDisplayName());
        assertTrue(service.isReturnHomeCommand(resolved));
        assertNotNull(resolved.getSteps());
        assertEquals(1, resolved.getSteps().length);
        MoveToPositionStep step = assertInstanceOf(MoveToPositionStep.class, resolved.getSteps()[0]);
        assertEquals(MoveSource.StoredHome, step.getSource());
    }

    @Test
    void prefersConfiguredReturnHomeIdWhenPresent() throws Exception {
        CommandResolutionService service = new CommandResolutionService(null, 64.0);
        CommandEntry configured = commandWithMoveSource("ReturnHome", MoveSource.OwnerPosition);
        TwCommandItemConfig config = configWithCommands(commandWithId("Follow"), configured);

        CommandEntry resolved = service.resolvePanelReturnHomeCommand(config, null);

        assertSame(configured, resolved);
    }

    @Test
    void prefersExistingStoredHomeCommandBeforeBuiltInFallback() throws Exception {
        CommandResolutionService service = new CommandResolutionService(null, 64.0);
        CommandEntry storedHome = commandWithMoveSource("SendHomeNow", MoveSource.StoredHome);
        TwCommandItemConfig config = configWithCommands(commandWithId("Follow"), storedHome);

        CommandEntry resolved = service.resolvePanelReturnHomeCommand(config, null);

        assertSame(storedHome, resolved);
        assertTrue(service.isReturnHomeCommand(resolved));
    }

    private TwCommandItemConfig configWithCommands(CommandEntry... commands) throws Exception {
        TwCommandItemConfig config = newConfig();
        setField(config, "commandList", commands);
        return config;
    }

    private CommandEntry commandWithId(String id) throws Exception {
        CommandEntry entry = new CommandEntry();
        setField(entry, "id", id);
        return entry;
    }

    private CommandEntry commandWithMoveSource(String id, MoveSource source) throws Exception {
        CommandEntry entry = commandWithId(id);
        MoveToPositionStep moveStep = new MoveToPositionStep();
        setField(moveStep, "source", source);
        setField(entry, "steps", new CommandStep[] { moveStep });
        return entry;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private TwCommandItemConfig newConfig() throws Exception {
        Constructor<TwCommandItemConfig> constructor = TwCommandItemConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
