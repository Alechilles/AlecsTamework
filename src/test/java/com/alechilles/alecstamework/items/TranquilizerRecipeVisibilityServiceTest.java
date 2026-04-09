package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranquilizerRecipeVisibilityServiceTest {

    @Test
    void isItemEnabledRespectsFeedFamilyToggles() throws Exception {
        TranquilizerRecipeVisibilityService service = new TranquilizerRecipeVisibilityService();
        Method isItemEnabled = TranquilizerRecipeVisibilityService.class.getDeclaredMethod(
                "isItemEnabled",
                String.class,
                TwGlobalConfig.AssetSetToggles.class
        );
        isItemEnabled.setAccessible(true);

        TwGlobalConfig.AssetSetToggles disabledFeeds = new TwGlobalConfig.AssetSetToggles(
                true,
                true,
                true,
                true,
                false,
                false
        );
        TwGlobalConfig.AssetSetToggles enabledFeeds = new TwGlobalConfig.AssetSetToggles(
                true,
                true,
                true,
                true,
                true,
                true
        );

        assertFalse((boolean) isItemEnabled.invoke(service, "Tw_Feed_Herbivore", disabledFeeds));
        assertFalse((boolean) isItemEnabled.invoke(service, "Tw_Feed_Carnivore", disabledFeeds));
        assertTrue((boolean) isItemEnabled.invoke(service, "Tw_Feed_Herbivore", enabledFeeds));
        assertTrue((boolean) isItemEnabled.invoke(service, "Tw_Feed_Carnivore", enabledFeeds));
        assertTrue((boolean) isItemEnabled.invoke(service, "Item_Unrelated", disabledFeeds));
    }
}
