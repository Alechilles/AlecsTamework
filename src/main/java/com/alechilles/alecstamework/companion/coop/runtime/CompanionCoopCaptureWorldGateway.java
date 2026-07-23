package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationGateway;

/**
 * World-thread receipt resolution and exact source retirement for one coop capture.
 *
 * <p>An implementation may confirm only an exact retirement receipt. Entity absence is retryable
 * evidence and cannot by itself prove that capture completed.</p>
 */
@FunctionalInterface
public interface CompanionCoopCaptureWorldGateway
        extends HytaleWorldOperationGateway<CompanionCoopCaptureRequest> {
}
