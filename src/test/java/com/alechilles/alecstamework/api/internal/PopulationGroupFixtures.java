package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import org.bson.BsonDocument;

/** Builds population-group config for API tests through the asset codec. */
public final class PopulationGroupFixtures {
    private PopulationGroupFixtures() {
    }

    /** One enabled group asset. {@code roles} is a JSON array body such as {@code "\"Sheep\", \"Cow\""}. */
    public static TwPopulationGroupConfig group(String assetId, String groupId, String roles,
                                                int maxOwned, int maxActive, String scope) {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "Enabled": true,
                  "Priority": 1,
                  "GroupId": "%s",
                  "RoleIds": [%s],
                  "Limits": {"MaxOwnedPerOwner": %d, "MaxActivePerOwner": %d, "Scope": "%s"}
                }
                """.formatted(groupId, roles, maxOwned, maxActive, scope)), new ExtraInfo());
        try {
            // The asset id normally comes from the asset store, which tests do not run.
            Field id = TwPopulationGroupConfig.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(config, assetId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return config;
    }

    public static PopulationGroupConfigIndex index(TwPopulationGroupConfig... groups) {
        return PopulationGroupConfigIndex.compile(List.of(groups), 7L);
    }
}
