package com.alechilles.alecstamework.api;

/** Whether a mutation-bound batch must reserve every unit or may reserve a safe prefix. */
public enum PopulationBatchAdmissionMode {
    EXACT,
    UP_TO
}
