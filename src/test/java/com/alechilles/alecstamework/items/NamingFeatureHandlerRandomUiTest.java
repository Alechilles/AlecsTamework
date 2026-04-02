package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NamingFeatureHandlerRandomUiTest {

    @Test
    void resolveRandomNamePoolIsEmptyWhenConfigHasNoRandomNamesId() throws Exception {
        NamingFeatureHandler handler = new NamingFeatureHandler(null, null);
        Method method = NamingFeatureHandler.class.getDeclaredMethod("resolveRandomNamePool", TwNameItemConfig.class);
        method.setAccessible(true);
        TwNameItemConfig config = newTwNameItemConfig();

        String[] resolved = (String[]) method.invoke(handler, config);

        assertNotNull(resolved);
        assertArrayEquals(new String[0], resolved);
    }

    @Test
    void resolveRandomNamePoolIsEmptyWhenConfigMissing() throws Exception {
        NamingFeatureHandler handler = new NamingFeatureHandler(null, null);
        Method method = NamingFeatureHandler.class.getDeclaredMethod("resolveRandomNamePool", TwNameItemConfig.class);
        method.setAccessible(true);
        String[] resolved = (String[]) method.invoke(handler, new Object[] { null });

        assertNotNull(resolved);
        assertArrayEquals(new String[0], resolved);
    }

    private static TwNameItemConfig newTwNameItemConfig() throws Exception {
        Constructor<TwNameItemConfig> constructor = TwNameItemConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
