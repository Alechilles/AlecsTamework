package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationResult;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationStatus;
import com.alechilles.alecstamework.api.CommandFamilyRosterView;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Verifies that a command-roster cull removes its durable membership. */
class CommandRosterCullUnlinkServiceTest {
    private static final UUID OWNER = UUID.fromString(
            "71000000-0000-0000-0000-000000000001"
    );
    private static final String FAMILY = "test:livestock";
    private static final String PROFILE = "profile-1";

    @Test
    void removesMatchingDurableRosterMembershipBeforeCull() throws Exception {
        CommandItemRegistry registry = new CommandItemRegistry();
        TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "RosterStorage":"OwnerCommandFamily",
                          "CommandFamilyId":"test:livestock",
                          "RequireOwner":true
                        }
                        """),
                new ExtraInfo()
        );
        setField(config, "id", "test-livestock-command");
        registry.register("test-livestock-command", "test:command", config);
        InMemoryRoster rosters = new InMemoryRoster();
        CommandRosterCullUnlinkService service =
                new CommandRosterCullUnlinkService(registry, rosters);

        CommandRosterCullUnlinkService.Preparation preparation =
                service.prepare(OWNER, PROFILE);
        assertTrue(preparation.isReady());
        assertTrue(service.remove(preparation).toCompletableFuture().join());

        assertTrue(rosters.getMembership(OWNER, FAMILY, PROFILE).isEmpty());
        assertEquals("test-livestock-command", rosters.lastRequest
                .requiredCommandConfigId());
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class InMemoryRoster implements CommandFamilyRosterApi {
        private boolean present = true;
        private CommandFamilyRosterMutationRequest lastRequest;

        @Override
        public Optional<CommandFamilyRosterView> get(
                UUID ownerUuid, String commandFamilyId
        ) {
            return ownerUuid.equals(OWNER) && FAMILY.equals(commandFamilyId)
                    ? Optional.of(new CommandFamilyRosterView(
                    OWNER, FAMILY, 4L,
                    present ? List.of(membership()) : List.of(), 0L
            )) : Optional.empty();
        }

        @Override
        public Optional<CommandFamilyRosterMembershipView> getMembership(
                UUID ownerUuid, String commandFamilyId, String profileId
        ) {
            return present && ownerUuid.equals(OWNER)
                    && FAMILY.equals(commandFamilyId) && PROFILE.equals(profileId)
                    ? Optional.of(membership()) : Optional.empty();
        }

        @Override
        public CompletionStage<CommandFamilyRosterMutationResult> upsert(
                CommandFamilyRosterMutationRequest request
        ) {
            return CompletableFuture.completedFuture(
                    CommandFamilyRosterMutationResult.unavailable("not-used")
            );
        }

        @Override
        public CompletionStage<CommandFamilyRosterMutationResult> remove(
                CommandFamilyRosterMutationRequest request
        ) {
            lastRequest = request;
            present = false;
            return CompletableFuture.completedFuture(
                    new CommandFamilyRosterMutationResult(
                            CommandFamilyRosterMutationStatus.APPLIED,
                            null, get(OWNER, FAMILY).orElse(null), null, false
                    )
            );
        }

        private CommandFamilyRosterMembershipView membership() {
            return new CommandFamilyRosterMembershipView(
                    OWNER, FAMILY, PROFILE, "TestRole", 7L,
                    CommandFamilyRosterMemberState.ACTIVE, null, true, null, 0L
            );
        }
    }
}
