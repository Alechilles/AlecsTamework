package com.alechilles.alecstamework.api.internal;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiMapperTest {
    @Test
    void safeToJsonSkipsInternalAssetMetadata() {
        String json = ApiMapper.safeToJson(new Gson(), new BeanWithInternalData());

        assertTrue(json.contains("\"id\":\"example\""));
        assertFalse(json.contains("\"data\""));
        assertFalse(json.contains("serializationError"));
    }

    private static final class BeanWithInternalData {
        @SuppressWarnings("unused")
        private final String id = "example";
        @SuppressWarnings("unused")
        private final InternalData data = new InternalData();
    }

    private static final class InternalData {
        @SuppressWarnings("unused")
        private final Class<?> declaredType = BeanWithInternalData.class;
    }
}
