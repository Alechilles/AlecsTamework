package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CommandLinksApi {
    Optional<CommandLinkView> getByProfileId(String profileId);

    Optional<CommandLinkView> getByNpcUuid(UUID npcUuid);

    Set<String> listLinkedToolIds(String profileId);

    Optional<Vector3View> getHomePosition(String profileId);

    boolean hasHomePosition(String profileId);
}
