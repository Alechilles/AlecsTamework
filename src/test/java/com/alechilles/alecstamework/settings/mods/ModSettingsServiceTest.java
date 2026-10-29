package com.alechilles.alecstamework.settings.mods;

import static com.alechilles.alecstamework.config.assets.TwModSettingFixtures.bool;
import static com.alechilles.alecstamework.config.assets.TwModSettingFixtures.group;
import static com.alechilles.alecstamework.config.assets.TwModSettingFixtures.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModSettingsServiceTest {
    private static final ModSettingsCatalog CATALOG = ModSettingsCatalog.build(List.of(new ModSettingsCatalog.Source(
            "Test:Mod", "Test Mod",
            group("Test_Spawning", bool("AH_WildDragons", true), integer("AH_Weight", 1, 0, 10)))));

    @TempDir
    Path dataDirectory;

    @Test
    void startupPassUsesSavedValuesAndRegeneratesOnceDeclarationsLoad() throws Exception {
        HytaleLogger logger = HytaleLogger.getLogger();
        new ModSettingValueStore(dataDirectory, logger).save(Map.of(
                "AH_WildDragons", new JsonPrimitive(false),
                "AH_Weight", new JsonPrimitive(99)));
        AtomicReference<ModSettingsCatalog> declared = new AtomicReference<>(ModSettingsCatalog.EMPTY);
        ModSettingsService service = new ModSettingsService(new ModSettingValueStore(dataDirectory, logger), logger, declared::get);
        AtomicInteger regenerations = new AtomicInteger();
        service.setRegenerator(() -> {
            regenerations.incrementAndGet();
            return CompletableFuture.completedFuture(ModSettingsService.ApplyOutcome.APPLIED);
        });

        // Patchwork's startup pass runs before the declarations load: saved choices still apply.
        assertEquals(new JsonPrimitive(false), service.patchworkSnapshot().get("AH_WildDragons"));

        declared.set(CATALOG);
        service.onDeclarationsChanged();
        assertEquals(1, regenerations.get(), "out-of-range saved value changed once declarations loaded");
        assertEquals(new JsonPrimitive(10), service.patchworkSnapshot().get("AH_Weight"));

        service.onDeclarationsChanged();
        assertEquals(1, regenerations.get(), "unchanged values must not regenerate again");
    }

    @Test
    void applySavesValuesAndNullResetsToDefault() {
        HytaleLogger logger = HytaleLogger.getLogger();
        ModSettingsService service = new ModSettingsService(new ModSettingValueStore(dataDirectory, logger), logger, () -> CATALOG);

        service.apply(Map.of("AH_WildDragons", new JsonPrimitive(false))).toCompletableFuture().join();
        ModSettingsService reloaded = new ModSettingsService(new ModSettingValueStore(dataDirectory, logger), logger, () -> CATALOG);
        assertEquals(new JsonPrimitive(false), reloaded.value("AH_WildDragons"));
        assertTrue(reloaded.isCustomized("AH_WildDragons"));

        Map<String, JsonPrimitive> reset = new HashMap<>();
        reset.put("AH_WildDragons", null);
        ModSettingsService.ApplyOutcome outcome = reloaded.apply(reset).toCompletableFuture().join();
        assertEquals(ModSettingsService.ApplyOutcome.NOT_REGENERATED, outcome, "no Patchwork runtime was attached");
        assertEquals(new JsonPrimitive(true), reloaded.value("AH_WildDragons"));
        assertFalse(reloaded.isCustomized("AH_WildDragons"));
    }
}
