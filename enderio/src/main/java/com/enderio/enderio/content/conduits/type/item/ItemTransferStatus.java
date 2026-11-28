package com.enderio.enderio.content.conduits.type.item;

/**
 * Outcome of the last attempt to extract through a connection, reported by the conduit probe.
 */
public enum ItemTransferStatus {
    NOT_CHECKED(false),
    MOVED(false),
    NO_SOURCE(false),
    NO_DESTINATION(true),
    SOURCE_EMPTY(true),
    EXTRACT_FILTERED(true),
    INSERT_FILTERED(true),
    DESTINATIONS_FULL(true),
    BANDWIDTH_USED(false);

    private final boolean idle;

    ItemTransferStatus(boolean idle) {
        this.idle = idle;
    }

    /**
     * Whether trying again right away is likely to be wasted work.
     */
    public boolean isIdle() {
        return idle;
    }
}
