package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;

/**
 * A bonded family's session length and summon cooldown with a companion's purchased talents
 * applied (plan 6 R18), read from the companion's stored snapshot. No live entity is touched.
 *
 * <p>For an active companion the snapshot can be older than the body, so a talent bought since
 * the last snapshot is not counted until the next one. A companion with no readable snapshot or
 * no talents gets the family's own timers.</p>
 */
public final class BondedTalentTimers {
    private static final String COMPONENTS = "Components";
    private static final String TALENTS = "TameworkTalents";

    private BondedTalentTimers() {
    }

    /** @param snapshots reads a profile's snapshot without blocking; {@code CompanionPersistenceModule::readSnapshot} */
    @Nonnull
    public static IndexBondedCompanionApi.Timers fromSnapshots(
            @Nonnull Function<UUID, CompletableFuture<SnapshotEnvelope>> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        return (record, family) -> {
            TwTalentConfig config = talentConfig(record.roleId());
            // Most roles have no talents: skip the snapshot read for them.
            if (config == null || !config.isEnabled()) {
                return CompletableFuture.completedFuture(family);
            }
            return snapshots.apply(record.profileId()).handle((snapshot, failure) ->
                    failure != null || snapshot == null ? family : adjust(family, snapshot, config));
        };
    }

    private static TwTalentConfig talentConfig(String roleId) {
        try {
            return TwTalentConfig.resolveForRole(roleId);
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static BondedCompanionPolicy adjust(BondedCompanionPolicy family, SnapshotEnvelope snapshot,
                                                TwTalentConfig config) {
        try {
            BsonDocument entity = CompanionSnapshots.entity(snapshot);
            if (!entity.isDocument(COMPONENTS) || !entity.getDocument(COMPONENTS).isDocument(TALENTS)) {
                return family;
            }
            TameworkTalentsComponent talents = TameworkTalentsComponent.CODEC.decode(
                    entity.getDocument(COMPONENTS).getDocument(TALENTS), new ExtraInfo());
            return BondedCompanionTalentTimerPolicyModifier.apply(family, talents, config);
        } catch (RuntimeException | LinkageError unreadable) {
            return family;
        }
    }
}
