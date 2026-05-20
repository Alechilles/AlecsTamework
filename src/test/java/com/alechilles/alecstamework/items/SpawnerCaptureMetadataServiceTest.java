package com.alechilles.alecstamework.items;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpawnerCaptureMetadataServiceTest {

    @Test
    void captureInfoCarriesCapturedModelIdForMetadataWriters() throws Exception {
        SpawnerCaptureMetadataService.CaptureInfo captureInfo = captureInfo("Model_Cat");

        assertEquals("Model_Cat", captureInfo.modelId());
    }

    private static SpawnerCaptureMetadataService.CaptureInfo captureInfo(String modelId) throws Exception {
        Constructor<SpawnerCaptureMetadataService.CaptureInfo> constructor =
                SpawnerCaptureMetadataService.CaptureInfo.class.getDeclaredConstructor(
                        String.class,
                        String.class,
                        Integer.class,
                        String.class,
                        String.class,
                        SpawnerCaptureMetadataService.CapturedName.class,
                        String.class
                );
        constructor.setAccessible(true);
        return constructor.newInstance(null, modelId, null, null, null, null, null);
    }
}
