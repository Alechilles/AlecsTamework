package com.alechilles.alecstamework.performance;

/**
 * Runtime work categories that can opt into adaptive retry backoff.
 */
public enum RuntimePressureDomain {
    NEEDS_RESOURCE_SEARCH,
    NEEDS_PATH_PREFLIGHT
}
