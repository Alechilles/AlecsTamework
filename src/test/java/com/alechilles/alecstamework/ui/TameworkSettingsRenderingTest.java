package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsRenderingTest {
    @Test
    void runtimeSettingsProduceACompletePageUpdate() throws Throwable {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe) singleton.get(null);
        TameworkSettingsPage page = new TameworkSettingsPage(null,
                (com.alechilles.alecstamework.Tamework) unsafe.allocateInstance(com.alechilles.alecstamework.Tamework.class),
                (com.hypixel.hytale.server.core.universe.world.World) unsafe.allocateInstance(com.hypixel.hytale.server.core.universe.world.World.class));
        for (String fieldName : new String[] {"statusLine", "warningLine"}) {
            Field field = TameworkSettingsPage.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(page, "");
        }
        Field values = TameworkSettingsPage.class.getDeclaredField("currentValues");
        values.setAccessible(true);
        values.set(page, TameworkSettingsValues.fromRuntime());
        UICommandBuilder commands = new UICommandBuilder();
        var events = new com.hypixel.hytale.server.core.ui.builder.UIEventBuilder();
        page.build(null, commands, events, null);
        assertTrue(java.util.Arrays.stream(commands.getCommands()).anyMatch(command ->
                "#TwSettingsApplyButton.Text".equals(command.selector)));
        assertTrue(java.util.Arrays.stream(events.getEvents()).anyMatch(event ->
                "#TwSettingsApplyButton".equals(event.selector)));
    }
}
