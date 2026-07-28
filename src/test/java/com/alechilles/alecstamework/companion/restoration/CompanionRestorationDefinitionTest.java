package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Restoration payload codec and exact-source validation contracts. */
class CompanionRestorationDefinitionTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final NpcAlias SOURCE =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final LifecycleRevision REVISION = new LifecycleRevision(5);
    private static final String PAYLOAD = "{\"health\":100}";

    @Test
    void deathAndLostRestorationsRoundTripExactly() {
        for (LifecycleState source : new LifecycleState[]{
                LifecycleState.DEAD_REVIVABLE,
                LifecycleState.LOST
        }) {
            CompanionRestorationRequest request = request(source);

            assertEquals(-12.5, request.placement().x());
            assertEquals(-500, request.requestedAtMs());
            assertEquals(
                    request,
                    CompanionRestorationDefinition.INSTANCE.decode(
                            CompanionRestorationDefinition.INSTANCE
                                    .encode(request)
                    )
            );
            assertFalse(JsonParser.parseString(
                    CompanionRestorationDefinition.INSTANCE.encode(request)
            ).getAsJsonObject().has("targetState"));
            CompanionRestorationOutcome outcome =
                    new CompanionRestorationOutcome(
                            PROFILE,
                            request.sourceSnapshot().snapshotId(),
                            TARGET,
                            "world-two",
                            REVISION.next().next(),
                            request.spawnReceiptKey(),
                            -400
                    );
            assertEquals(
                    outcome,
                    CompanionRestorationEventCodec.decode(
                            CompanionRestorationEventCodec.VERSION,
                            CompanionRestorationEventCodec.encode(outcome)
                    )
            );
        }
    }

    @Test
    void provisionedDormantRevivalRoundTripsWithoutLiveTargetFacts() {
        CompanionRestorationRequest request =
                CompanionRestorationRequest.reviveProvisionedDormant(
                        PROFILE,
                        REVISION,
                        snapshot(
                                LifecycleState.DEAD_REVIVABLE,
                                REVISION
                        ),
                        -500
                );

        String encoded =
                CompanionRestorationDefinition.INSTANCE.encode(request);
        assertEquals(
                request,
                CompanionRestorationDefinition.INSTANCE.decode(encoded)
        );
        assertFalse(request.restoresLive());
        assertEquals(
                LifecycleState.PROVISIONED_DORMANT,
                request.targetState()
        );
        assertNull(request.projection());
        assertNull(request.targetAlias());
        assertNull(request.placement());
        assertNull(request.spawnReceiptKey());
        assertNull(request.targetWorldKey());

        CompanionRestorationOutcome outcome =
                new CompanionRestorationOutcome(
                        PROFILE,
                        request.sourceSnapshot().snapshotId(),
                        LifecycleState.PROVISIONED_DORMANT,
                        null,
                        null,
                        REVISION.next(),
                        null,
                        -400
                );
        assertEquals(
                outcome,
                CompanionRestorationEventCodec.decode(
                        CompanionRestorationEventCodec.VERSION,
                        CompanionRestorationEventCodec.encode(outcome)
                )
        );
    }

    @Test
    void rejectsWrongKindFutureOrNonDormantSources() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionRestorationRequest(
                        PROFILE,
                        REVISION,
                        LifecycleState.LOST,
                        snapshot(
                                LifecycleState.DEAD_REVIVABLE,
                                REVISION
                        ),
                        projection(snapshot(
                                LifecycleState.DEAD_REVIVABLE,
                                REVISION
                        )),
                        TARGET,
                        placement(),
                        "spawn-receipt",
                        -500
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionRestorationRequest(
                        PROFILE,
                        REVISION,
                        LifecycleState.DEAD_REVIVABLE,
                        snapshot(
                                LifecycleState.DEAD_REVIVABLE,
                                REVISION.next()
                        ),
                        projection(snapshot(
                                LifecycleState.DEAD_REVIVABLE,
                                REVISION.next()
                        )),
                        TARGET,
                        placement(),
                        "spawn-receipt",
                        -500
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionRestorationRequest(
                        PROFILE,
                        REVISION,
                        LifecycleState.ACTIVE,
                        snapshot(
                                LifecycleState.DEAD_REVIVABLE,
                                REVISION
                        ),
                        projection(snapshot(
                                LifecycleState.DEAD_REVIVABLE,
                                REVISION
                        )),
                        TARGET,
                        placement(),
                        "spawn-receipt",
                        -500
                )
        );
    }

    @Test
    void rejectsWrongVersionKindOrAliasedProjection() {
        CompanionSnapshot source = snapshot(
                LifecycleState.DEAD_REVIVABLE,
                REVISION
        );
        RestorationProjection valid = projection(source);
        assertThrows(
                IllegalArgumentException.class,
                () -> requestWithProjection(new RestorationProjection(
                        SOURCE,
                        encoded(
                                DormantSourceEvidence.Kind
                                        .DESTRUCTIVE_REMOVAL.snapshotKind(),
                                2
                        )
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> requestWithProjection(new RestorationProjection(
                        SOURCE,
                        encoded(
                                CompanionFullStateProjection.KIND,
                                CompanionFullStateProjection.VERSION + 1
                        )
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> requestWithProjection(new RestorationProjection(
                        TARGET,
                        valid.fullState()
                ))
        );
    }

    @Test
    void provisionedDormantRevivalRejectsLostOrLiveTargetEvidence() {
        CompanionSnapshot death = snapshot(
                LifecycleState.DEAD_REVIVABLE, REVISION
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionRestorationRequest(
                        PROFILE,
                        REVISION,
                        LifecycleState.LOST,
                        snapshot(LifecycleState.LOST, REVISION),
                        LifecycleState.PROVISIONED_DORMANT,
                        null,
                        null,
                        null,
                        null,
                        -500
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionRestorationRequest(
                        PROFILE,
                        REVISION,
                        LifecycleState.DEAD_REVIVABLE,
                        death,
                        LifecycleState.PROVISIONED_DORMANT,
                        projection(death),
                        TARGET,
                        placement(),
                        "spawn-receipt",
                        -500
                )
        );
    }

    private CompanionRestorationRequest request(LifecycleState source) {
        return new CompanionRestorationRequest(
                PROFILE,
                REVISION,
                source,
                snapshot(source, new LifecycleRevision(4)),
                projection(snapshot(source, new LifecycleRevision(4))),
                TARGET,
                placement(),
                "spawn-receipt-" + source.name().toLowerCase(),
                -500
        );
    }

    private CompanionSpawnPlacement placement() {
        return new CompanionSpawnPlacement(
                "world-two", -12.5, -63.05, -4.5,
                -0.25f, -1.5f, -0.5f
        );
    }

    private CompanionSnapshot snapshot(
            LifecycleState source,
            LifecycleRevision sourceRevision
    ) {
        return new CompanionSnapshot(
                SnapshotId.parse("50000000-0000-0000-0000-000000000001"),
                PROFILE,
                source == LifecycleState.DEAD_REVIVABLE
                        ? DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind()
                        : DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL.snapshotKind(),
                1,
                PAYLOAD,
                Sha256Hash.ofUtf8(PAYLOAD),
                sourceRevision,
                true,
                -600
        );
    }

    private RestorationProjection projection(CompanionSnapshot source) {
        return new RestorationProjection(
                SOURCE,
                encoded(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                )
        );
    }

    private SnapshotCodecRegistry.EncodedSnapshot encoded(
            com.alechilles.alecstamework.companion.snapshot.SnapshotKind kind,
            int version
    ) {
        String payload = "{\"state\":\"frozen\"}";
        return new SnapshotCodecRegistry.EncodedSnapshot(
                kind,
                version,
                payload,
                Sha256Hash.ofUtf8(payload)
        );
    }

    private CompanionRestorationRequest requestWithProjection(
            RestorationProjection projection
    ) {
        return new CompanionRestorationRequest(
                PROFILE,
                REVISION,
                LifecycleState.DEAD_REVIVABLE,
                snapshot(LifecycleState.DEAD_REVIVABLE, REVISION),
                projection,
                TARGET,
                placement(),
                "spawn-receipt",
                -500
        );
    }
}
