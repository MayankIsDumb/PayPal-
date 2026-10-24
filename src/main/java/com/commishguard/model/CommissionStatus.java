package com.commishguard.model;

public enum CommissionStatus {
    /** Contract parsed but no money is held yet. */
    QUOTED,
    /** PayPal order created; funds authorized or awaiting buyer approval. */
    HELD,
    /** Deliverable passed every deterministic check; capture is allowed. */
    VERIFIED,
    /** Payment captured. Terminal. */
    RELEASED,
    /** Deliverable failed verification. A corrected upload is allowed. */
    FAILED,
    /** Buyer or merchant aborted. Terminal. */
    CANCELED
}
