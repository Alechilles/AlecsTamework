package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.api.CaptureRequirementSpec;
import javax.annotation.Nonnull;

/** Internal generation-fenced evaluator for registered capture requirements. */
public interface CaptureRequirementRuntime {
    long captureRequirementGeneration();

    @Nonnull
    CaptureRequirementDecision evaluateCaptureRequirement(
            @Nonnull CaptureRequirementSpec spec,
            @Nonnull CaptureRequirementContext context,
            long expectedGeneration
    );
}
