package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.access.CompanionAccess;
import com.alechilles.alecstamework.access.OwnerGrants;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.api.access.AccessChangeResult;
import com.alechilles.alecstamework.api.access.AccessGrantView;
import com.alechilles.alecstamework.api.access.AccessGroupProvider;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.api.access.SharedAccessApi;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Public adapter over {@link SharedAccessService}. Holds no state of its own. */
public final class SharedAccessFacade implements SharedAccessApi {
    private final SharedAccessService service;
    private final Function<UUID, CompanionRecord> recordByProfileId;

    /**
     * @param recordByProfileId the companion index lookup; returns null for an unknown profile
     */
    public SharedAccessFacade(@Nonnull SharedAccessService service,
                              @Nonnull Function<UUID, CompanionRecord> recordByProfileId) {
        this.service = Objects.requireNonNull(service, "service");
        this.recordByProfileId = Objects.requireNonNull(recordByProfileId, "recordByProfileId");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean enabled() {
        return service.isEnabled();
    }

    @Override
    public AccessLevel maxLevel() {
        return service.maxLevel();
    }

    @Override
    public AccessLevel level(@Nullable UUID ownerUuid, @Nullable UUID actorUuid, @Nullable UUID profileId) {
        return service.level(ownerUuid, actorUuid, profileId);
    }

    @Override
    public AccessLevel levelForProfile(UUID profileId, @Nullable UUID actorUuid) {
        Objects.requireNonNull(profileId, "profileId");
        return CompanionAccess.level(recordByProfileId.apply(profileId), actorUuid);
    }

    @Override
    public List<AccessGrantView> grants(UUID ownerUuid) {
        OwnerGrants grants = service.grants(Objects.requireNonNull(ownerUuid, "ownerUuid"));
        List<AccessGrantView> views = new ArrayList<>(grants.players().size() + grants.groups().size());
        for (OwnerGrants.PlayerGrant grant : grants.players().values()) {
            views.add(new AccessGrantView(grant.playerId(), grant.playerName(), null, grant.level()));
        }
        grants.groups().forEach((groupId, level) -> views.add(new AccessGrantView(null, null, groupId, level)));
        return List.copyOf(views);
    }

    @Override
    public boolean isPrivate(UUID ownerUuid, UUID profileId) {
        return service.isPrivate(ownerUuid, profileId);
    }

    @Override
    public Set<UUID> ownersSharingWith(UUID actorUuid) {
        return Set.copyOf(service.ownersSharingWith(actorUuid));
    }

    @Override
    public AccessChangeResult grantPlayer(UUID ownerUuid, UUID playerUuid, @Nullable String playerName,
                                          AccessLevel level) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(level, "level");
        return service.grantPlayer(ownerUuid, playerUuid, playerName, level);
    }

    @Override
    public AccessChangeResult revokePlayer(UUID ownerUuid, UUID playerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(playerUuid, "playerUuid");
        return service.revokePlayer(ownerUuid, playerUuid);
    }

    @Override
    public AccessChangeResult setGroup(UUID ownerUuid, String groupId, @Nullable AccessLevel level) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(groupId, "groupId");
        return service.setGroup(ownerUuid, groupId, level);
    }

    @Override
    public AccessChangeResult setPrivate(UUID ownerUuid, UUID profileId, boolean isPrivate) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(profileId, "profileId");
        return service.setPrivate(ownerUuid, profileId, isPrivate);
    }

    @Override
    public AutoCloseable registerGroup(AccessGroupProvider provider) {
        return service.registerGroup(Objects.requireNonNull(provider, "provider"));
    }
}
