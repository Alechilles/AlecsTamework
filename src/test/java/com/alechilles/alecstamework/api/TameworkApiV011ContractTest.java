package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.api.internal.BondedOnlyTameworkApi;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Public behavior checks for the Tamework 0.11 Activity API V2 surface. */
class TameworkApiV011ContractTest {
    @Test
    void unavailableActivityFacadeFailsClosedWithTheV2SubscriptionShape() {
        ActivityFeedApi api = ActivityFeedApi.unavailable();

        ActivityFeedStatus before = api.status("contract-consumer");
        ActivityFeedSubscription subscription = api.subscribe(
                "contract-consumer",
                new ActivityFilter(Set.of(ActivityDomain.MANAGED_CARE), Set.of()),
                ignored -> { }
        );

        assertFalse(before.available());
        assertFalse(before.subscribed());
        assertEquals("contract-consumer", subscription.consumerId());
        subscription.close();
        subscription.close();
    }

    @Test
    void degradedFacadeReportsTheV2ApiVersionWithoutAdvertisingTheUnavailableFeed() {
        TameworkApi api = new BondedOnlyTameworkApi(
                BondedCompanionApi.unavailable());

        assertEquals("0.11.0", api.getApiVersion());
        assertFalse(api.getCapabilities().contains(
                TameworkApiCapability.ACTIVITY_FEED_V2));
        assertFalse(api.activities().status("contract-consumer").available());
    }
}
