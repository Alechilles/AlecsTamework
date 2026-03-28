package com.alechilles.alecstamework.api;

import java.util.EnumSet;

public interface TameworkApi {
    String getApiVersion();

    EnumSet<TameworkApiCapability> getCapabilities();

    NpcProfilesApi profiles();

    CommandLinksApi commandLinks();

    PolicyApi policies();

    ProfileDataApi profileData();

    TameworkEventsApi events();

    TameworkConfigReadApi configs();

    DiagnosticsApi diagnostics();
}

