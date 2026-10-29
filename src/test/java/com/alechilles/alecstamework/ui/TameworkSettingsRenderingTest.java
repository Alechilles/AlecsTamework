package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.settings.NameFilterSettings;
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
        // Render a prepared form without initializing plugin storage or a live world.
        TameworkSettingsPage page = (TameworkSettingsPage) unsafe.allocateInstance(TameworkSettingsPage.class);
        for (String fieldName : new String[] {"statusLine", "warningLine"}) {
            Field field = TameworkSettingsPage.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(page, "");
        }
        Field values = TameworkSettingsPage.class.getDeclaredField("currentValues");
        values.setAccessible(true);
        values.set(page, TameworkSettingsValues.fromRuntime());
        Field nameFilter = TameworkSettingsPage.class.getDeclaredField("currentNameFilter");
        nameFilter.setAccessible(true);
        nameFilter.set(page, NameFilterSettings.DEFAULT);
        Field deploy = TameworkSettingsPage.class.getDeclaredField("currentDeploy");
        deploy.setAccessible(true);
        deploy.set(page, com.alechilles.alecstamework.settings.DeployPlaces.DEFAULT);
        // No mod settings service: the Mod Settings tab renders its empty state.
        Field modTab = TameworkSettingsPage.class.getDeclaredField("modTab");
        modTab.setAccessible(true);
        modTab.set(page, new TameworkSettingsModTab(
                (com.hypixel.hytale.server.core.universe.PlayerRef) unsafe.allocateInstance(com.hypixel.hytale.server.core.universe.PlayerRef.class),
                (com.alechilles.alecstamework.Tamework) unsafe.allocateInstance(com.alechilles.alecstamework.Tamework.class)));
        UICommandBuilder commands = new UICommandBuilder();
        var events = new com.hypixel.hytale.server.core.ui.builder.UIEventBuilder();
        page.build(null, commands, events, null);
        assertTrue(java.util.Arrays.stream(commands.getCommands()).anyMatch(command ->
                "#TwSettingsApplyButton.Text".equals(command.selector)));
        assertTrue(java.util.Arrays.stream(events.getEvents()).anyMatch(event ->
                "#TwSettingsApplyButton".equals(event.selector)));
    }
}
