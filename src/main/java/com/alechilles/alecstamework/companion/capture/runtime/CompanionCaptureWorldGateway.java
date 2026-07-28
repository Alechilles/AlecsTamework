package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationGateway;

/**
 * World-thread inventory receipt resolution and exact target retirement for one capture.
 *
 * <p>Implementations may confirm only after observing the exact captured artifact. Source or
 * target absence alone is never proof that this operation completed.</p>
 */
@FunctionalInterface
public interface CompanionCaptureWorldGateway
        extends HytaleWorldOperationGateway<CompanionCaptureRequest> {
}
