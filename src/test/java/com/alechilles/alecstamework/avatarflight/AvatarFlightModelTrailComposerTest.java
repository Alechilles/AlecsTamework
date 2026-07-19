package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.protocol.EntityPart;
import com.hypixel.hytale.protocol.ModelTrail;
import org.junit.jupiter.api.Test;

/** Verifies managed flight trails can be replaced without disturbing model-authored trails. */
class AvatarFlightModelTrailComposerTest {

    @Test
    void composeReplacesManagedTrailsAndTargetsTheModelItself() {
        ModelTrail previousBurst = trail("Dragon_Burst", "R-WingTip", EntityPart.Entity);
        ModelTrail unrelated = trail("Dragon_Tail", "TailTip", EntityPart.Entity);
        ModelTrail desired = trail("Dragon_Glide", "L-WingTip", EntityPart.Entity);

        ModelTrail[] result = AvatarFlightModelTrailComposer.compose(
                new ModelTrail[]{previousBurst, unrelated},
                new ModelTrail[][]{{previousBurst}, {desired}},
                new ModelTrail[]{desired}
        );

        assertEquals(2, result.length);
        assertEquals("Dragon_Tail", result[0].trailId);
        assertEquals("Dragon_Glide", result[1].trailId);
        assertEquals(EntityPart.Self, result[1].targetEntityPart);
        assertEquals(EntityPart.Entity, desired.targetEntityPart,
                "composition must not mutate the cached asset definition");
    }

    @Test
    void composeWithoutDesiredTrailsClearsEveryManagedVariant() {
        ModelTrail managed = trail("Dragon_Glide", "R-WingTip", EntityPart.Entity);

        ModelTrail[] result = AvatarFlightModelTrailComposer.compose(
                new ModelTrail[]{managed}, new ModelTrail[][]{{managed}}, null);

        assertEquals(0, result.length);
    }

    private static ModelTrail trail(String trailId, String nodeName, EntityPart entityPart) {
        ModelTrail trail = new ModelTrail();
        trail.trailId = trailId;
        trail.targetNodeName = nodeName;
        trail.targetEntityPart = entityPart;
        return trail;
    }
}
