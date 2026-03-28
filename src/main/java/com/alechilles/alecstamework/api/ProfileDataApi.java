package com.alechilles.alecstamework.api;

import java.util.Map;
import java.util.Optional;

public interface ProfileDataApi {
    Optional<String> get(String profileId, String namespace, String key);

    Map<String, String> list(String profileId, String namespace);

    boolean put(String profileId, String namespace, String key, String jsonPayload);

    boolean delete(String profileId, String namespace, String key);
}

