package com.enderio.enderio.content.conduits.type.item;

/**
 * Runtime-only state of one extracting connection: what happened last, and whether it is asleep.
 * Never saved, so every connection starts awake.
 */
public final class ItemExtractState {
    private static final int IDLE_CYCLES_BEFORE_SLEEP = 3;

    private ItemTransferStatus status = ItemTransferStatus.NOT_CHECKED;
    private int lastMoved;
    private int idleCycles;
    private long wakeAt;
    private long networkRevision;

    /**
     * @return whether the connection should be skipped. Any change to the network's cache wakes it.
     */
    public boolean isSleeping(long gameTime, long currentNetworkRevision) {
        if (networkRevision != currentNetworkRevision) {
            networkRevision = currentNetworkRevision;
            idleCycles = 0;
            wakeAt = 0;
        }

        return gameTime < wakeAt;
    }

    public void record(ItemTransferStatus result, int moved, long gameTime, int tickRate, int maxSleepTicks) {
        status = result;
        lastMoved = moved;

        if (!result.isIdle()) {
            idleCycles = 0;
            wakeAt = 0;
            return;
        }

        idleCycles = Math.min(idleCycles + 1, 1000);
        if (idleCycles >= IDLE_CYCLES_BEFORE_SLEEP && maxSleepTicks > 0) {
            // Double the wait each time, up to the configured limit.
            int doublings = Math.min(idleCycles - IDLE_CYCLES_BEFORE_SLEEP + 1, 10);
            wakeAt = gameTime + Math.min((long) tickRate << doublings, maxSleepTicks);
        }
    }

    public ItemTransferStatus status() {
        return status;
    }

    public int lastMoved() {
        return lastMoved;
    }

    public long ticksUntilAwake(long gameTime) {
        return Math.max(0, wakeAt - gameTime);
    }
}
