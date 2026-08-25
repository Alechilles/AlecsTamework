package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Public command UI presentation values produced by the runtime assembler. */
class CommandUiSnapshotAssemblerTest {
    @Test
    void configuredCommandKeyBecomesDisplayReadyText() throws Exception {
        Constructor<TwCommandItemConfig> constructor =
                TwCommandItemConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCommandItemConfig config = constructor.newInstance();
        TwCommandItemConfig.CommandEntry command =
                new TwCommandItemConfig.CommandEntry();
        set(command, "id", "Follow");
        set(command, "displayName",
                "tamework.ui.linkedPanel.bonded.loading");
        set(config, "commandList",
                new TwCommandItemConfig.CommandEntry[]{command});

        var option = CommandUiSnapshotAssembler.commandOptions(
                config, "Follow", Map.of(), "en-US").getFirst();

        assertEquals("tamework.ui.linkedPanel.bonded.loading",
                option.localizationSource());
        assertNotEquals(option.localizationSource(), option.label());
    }

    private static void set(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
