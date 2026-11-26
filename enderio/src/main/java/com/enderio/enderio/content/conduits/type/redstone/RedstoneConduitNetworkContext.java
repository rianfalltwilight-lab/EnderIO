package com.enderio.enderio.content.conduits.type.redstone;

import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.api.conduits.network.ConduitNetworkContext;
import com.enderio.enderio.api.conduits.network.ConduitNetworkContextType;
import com.enderio.enderio.api.filter.RedstoneOutputFilterContext;
import net.minecraft.world.item.DyeColor;

import java.util.Arrays;
import java.util.Set;

public class RedstoneConduitNetworkContext implements ConduitNetworkContext<RedstoneConduitNetworkContext>, RedstoneOutputFilterContext {

    // Redstone context is not saved as it is recalculated every graph tick if the
    // network is active.
    public static final ConduitNetworkContextType<RedstoneConduitNetworkContext> TYPE = new ConduitNetworkContextType<>(null,
            RedstoneConduitNetworkContext::new);

    private boolean isActive;
    private static final int CHANNEL_COUNT = DyeColor.values().length;
    private int[] previousChannelSignals = new int[CHANNEL_COUNT];
    private int[] channelSignals = new int[CHANNEL_COUNT];

    private NewNetworkDelay newNetworkDelay = NewNetworkDelay.NEW;

    public RedstoneConduitNetworkContext() {
    }

    public boolean isNew() {
        return newNetworkDelay != NewNetworkDelay.OLD;
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isActive(DyeColor color) {
        return getSignal(color) > 0;
    }

    public int getSignal(DyeColor color) {
        return channelSignals[color.ordinal()];
    }

    // Used for change detection in the ticker.
    public int getSignalLastTick(DyeColor color) {
        return previousChannelSignals[color.ordinal()];
    }

    public void nextTick() {
        if (newNetworkDelay == NewNetworkDelay.NEW) {
            newNetworkDelay = NewNetworkDelay.NEW_DECAY;
        } else if (newNetworkDelay == NewNetworkDelay.NEW_DECAY) {
            newNetworkDelay = NewNetworkDelay.OLD;
        }

        // Save for change detection
        var reusable = previousChannelSignals;
        previousChannelSignals = channelSignals;
        channelSignals = reusable;
        Arrays.fill(channelSignals, 0);
        isActive = false;
    }

    public void setSignal(DyeColor color, int signal) {
        if (signal > getSignal(color)) {
            channelSignals[color.ordinal()] = signal;
            isActive = true;
        }
    }

    @Override
    public RedstoneConduitNetworkContext mergeWith(RedstoneConduitNetworkContext other) {
        return copy();
    }

    @Override
    public RedstoneConduitNetworkContext split(ConduitNetwork selfNetwork,
            Set<? extends ConduitNetwork> allNetworks) {
        return copy();
    }

    private RedstoneConduitNetworkContext copy() {
        var copy = new RedstoneConduitNetworkContext();
        copy.isActive = isActive;
        System.arraycopy(channelSignals, 0, copy.channelSignals, 0, CHANNEL_COUNT);
        return copy;
    }

    @Override
    public ConduitNetworkContextType<RedstoneConduitNetworkContext> type() {
        return TYPE;
    }

    // Because the context is cleared before the graph ticks, we need to represent
    // "newness" as three states.
    private enum NewNetworkDelay {
        NEW, NEW_DECAY, OLD
    }
}
