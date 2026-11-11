package com.alechilles.alecstamework.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.access.SharedAccessService.ChangeResult;
import com.alechilles.alecstamework.api.access.AccessGroupMembership;
import com.alechilles.alecstamework.api.access.AccessGroupProvider;
import com.alechilles.alecstamework.api.access.AccessLevel;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies who gets which level on another player's companions. */
class SharedAccessServiceTest {
    private static final String GUILD = "test:guild";

    private final SharedAccessService service = SharedAccessService.get();
    private final UUID owner = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID guildmate = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID companion = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service.clearForTests();
    }

    @AfterEach
    void tearDown() {
        service.clearForTests();
    }

    @Test
    void namedGrantGivesThatPlayerItsLevelAndOthersNothing() {
        service.grantPlayer(owner, friend, "Friend", AccessLevel.HANDLER);

        assertEquals(AccessLevel.OWNER, service.level(owner, owner, companion));
        assertEquals(AccessLevel.HANDLER, service.level(owner, friend, companion));
        assertEquals(AccessLevel.NONE, service.level(owner, stranger, companion));
    }

    @Test
    void groupShareReachesOnlyMembersAndTheHigherGrantWins() throws Exception {
        try (AutoCloseable ignored = service.registerGroup(guildOf(Set.of(guildmate, friend)))) {
            service.setGroup(owner, GUILD, AccessLevel.CARETAKER);
            service.grantPlayer(owner, friend, "Friend", AccessLevel.MANAGER);

            assertEquals(AccessLevel.CARETAKER, service.level(owner, guildmate, companion));
            assertEquals(AccessLevel.MANAGER, service.level(owner, friend, companion));
            assertEquals(AccessLevel.NONE, service.level(owner, stranger, companion));
            assertEquals(Set.of(owner), service.ownersSharingWith(guildmate));
        }
        // The provider is gone: the saved choice stays but reaches nobody.
        assertEquals(AccessLevel.NONE, service.level(owner, guildmate, companion));
    }

    @Test
    void privateAndNeverSharedCompanionsStayWithTheOwner() {
        UUID privateCompanion = UUID.randomUUID();
        UUID bonded = UUID.randomUUID();
        service.grantPlayer(owner, friend, "Friend", AccessLevel.MANAGER);
        service.setPrivate(owner, privateCompanion, true);
        service.setNeverShared(bonded::equals);

        assertEquals(AccessLevel.NONE, service.level(owner, friend, privateCompanion));
        assertEquals(AccessLevel.NONE, service.level(owner, friend, bonded));
        assertEquals(AccessLevel.MANAGER, service.level(owner, friend, companion));
        assertEquals(AccessLevel.OWNER, service.level(owner, owner, privateCompanion));
    }

    @Test
    void serverSettingsCapExistingGrantsAndCanTurnSharingOff() {
        service.grantPlayer(owner, friend, "Friend", AccessLevel.MANAGER);

        service.configure(true, AccessLevel.CARETAKER);
        assertEquals(AccessLevel.CARETAKER, service.level(owner, friend, companion));
        assertEquals(ChangeResult.LEVEL_NOT_ALLOWED,
                service.grantPlayer(owner, stranger, "Stranger", AccessLevel.HANDLER));

        service.configure(false, AccessLevel.MANAGER);
        assertEquals(AccessLevel.NONE, service.level(owner, friend, companion));
        assertEquals(AccessLevel.OWNER, service.level(owner, owner, companion));
    }

    @Test
    void anOwnerCannotShareWithMoreThanTheLimitOrWithThemselves() {
        for (int i = 0; i < SharedAccessService.MAX_PLAYER_GRANTS_PER_OWNER; i++) {
            service.grantPlayer(owner, UUID.randomUUID(), "Player" + i, AccessLevel.CARETAKER);
        }

        assertEquals(ChangeResult.TOO_MANY_PLAYERS,
                service.grantPlayer(owner, friend, "Friend", AccessLevel.CARETAKER));
        assertEquals(ChangeResult.SELF, service.grantPlayer(owner, owner, "Owner", AccessLevel.CARETAKER));
    }

    @Test
    void grantsAndPrivateFlagsSurviveARestart(@TempDir Path dataDirectory) {
        service.start(dataDirectory);
        service.grantPlayer(owner, friend, "Friend", AccessLevel.HANDLER);
        service.setPrivate(owner, companion, true);
        service.close();

        service.clearForTests();
        service.start(dataDirectory);

        assertEquals(AccessLevel.HANDLER, service.level(owner, friend, UUID.randomUUID()));
        assertEquals(AccessLevel.NONE, service.level(owner, friend, companion));
        assertEquals("Friend", service.grants(owner).players().get(friend).playerName());
        assertEquals(Set.of(owner), service.ownersSharingWith(friend));
    }

    private static AccessGroupProvider guildOf(Set<UUID> members) {
        return new AccessGroupProvider() {
            @Override
            public String id() {
                return GUILD;
            }

            @Override
            public String displayNameKey() {
                return "test.guild";
            }

            @Override
            public AccessGroupMembership membership(UUID ownerUuid, UUID actorUuid) {
                return members.contains(actorUuid) ? AccessGroupMembership.MEMBER : AccessGroupMembership.NOT_MEMBER;
            }
        };
    }
}
