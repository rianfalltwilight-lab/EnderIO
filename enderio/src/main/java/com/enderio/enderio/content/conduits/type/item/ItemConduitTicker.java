package com.enderio.enderio.content.conduits.type.item;

import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.config.conduits.ConduitsConfig;
import com.enderio.enderio.content.conduits.network.ConduitNetworkImpl;
import com.enderio.enderio.api.EnderIOCapabilities;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.connection.path.ConduitConnectionPath;
import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.api.conduits.ticker.ConduitTickerBase;
import com.enderio.enderio.api.filter.ItemFilterContext;
import com.enderio.enderio.api.filter.ItemFilterDirection;
import com.enderio.enderio.content.filters.item.NonMutatingItemFilter;
import com.enderio.enderio.init.EIOConduitTypes;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.List;

public class ItemConduitTicker extends ConduitTickerBase<ItemConduit> {

    public static final ItemConduitTicker INSTANCE = new ItemConduitTicker();

    private ItemConduitTicker() {
        super(EIOConduitTypes.ITEM::get);
    }

    /**
     * Upper bound on separate insertions per extracting connection and cycle, so that unstackable items from a very
     * large source cannot turn one cycle into hundreds of inventory operations.
     */
    private static final int MAX_BATCHES_PER_CYCLE = 128;

    @Override
    protected void tickNetwork(ServerLevel level, ConduitNetwork network, List<Holder<Conduit<?, ?>>> tickableConduits) {
        var usedPerPath = new Object2IntOpenHashMap<ConduitConnectionPath>();
        usedPerPath.defaultReturnValue(0);

        for (var channel : network.allChannels()) {
            tickChannel(level, network, tickableConduits, usedPerPath, channel);
        }
    }

    private void tickChannel(ServerLevel level, ConduitNetwork network, List<Holder<Conduit<?, ?>>> tickableConduits,
            Object2IntOpenHashMap<ConduitConnectionPath> usedPerPath, DyeColor channel) {
        long gameTime = level.getGameTime();
        long networkRevision = network instanceof ConduitNetworkImpl impl ? impl.cacheRevision() : 0;
        int maxSleepTicks = ConduitsConfig.COMMON.ITEM_CONDUIT_MAX_SLEEP_TICKS.get();

        for (var extractConnection : network.extractConnections(channel)) {
            var extractConduit = extractConnection.node().conduit(conduitType());

            // If this conduit isn't allowed to tick, skip it.
            if (!tickableConduits.contains(extractConduit)) {
                continue;
            }

            var nodeData = extractConnection.node().getOrCreateNodeData(EIOConduitTypes.NodeData.ITEM.get());
            var state = nodeData.extractState(extractConnection.connectionSide());
            if (state.isSleeping(gameTime, networkRevision)) {
                continue;
            }

            int tickRate = extractConduit.value().networkTickRate();
            var config = extractConnection.connectionConfig(EIOConduitTypes.ConnectionTypes.ITEM.get());
            int interval = effectiveInterval(config.extractInterval(), tickRate);
            // The conduit's own cycle is already scheduled; only longer intervals need to be tracked.
            if (interval > tickRate && !state.isDue(gameTime, interval)) {
                continue;
            }
            state.ran(gameTime);

            var insertPaths = network.insertConnectionsFrom(extractConnection);
            if (insertPaths.isEmpty()) {
                state.record(ItemTransferStatus.NO_DESTINATION, 0, gameTime, interval, maxSleepTicks);
                continue;
            }

            // Get extract handler from the connection.
            IItemHandler extractHandler = extractConnection.getSidedCapability(Capabilities.ItemHandler.BLOCK);
            if (extractHandler == null) {
                state.record(ItemTransferStatus.NO_SOURCE, 0, gameTime, interval, maxSleepTicks);
                continue;
            }

            var attempt = new Attempt();
            // A longer interval moves proportionally more at once, never more per tick than the conduit allows.
            transfer(extractConnection, extractConduit.value(), extractHandler, insertPaths, nodeData, usedPerPath, attempt,
                    interval / tickRate);
            state.record(attempt.status(), attempt.moved, gameTime, interval, maxSleepTicks);
        }
    }

    /** The configured interval, rounded up to a whole number of the conduit's cycles. */
    static int effectiveInterval(int configured, int tickRate) {
        return Math.max(1, (configured + tickRate - 1) / tickRate) * tickRate;
    }

    /** What happened while trying to move items out of one connection. */
    private static final class Attempt {
        private int moved;
        private boolean sawItem;
        private boolean passedExtractFilter;
        private boolean insertRefused;
        private boolean insertFiltered;
        private boolean outOfBandwidth;

        private ItemTransferStatus status() {
            if (moved > 0) {
                return ItemTransferStatus.MOVED;
            }
            if (!sawItem) {
                return ItemTransferStatus.SOURCE_EMPTY;
            }
            if (!passedExtractFilter) {
                return ItemTransferStatus.EXTRACT_FILTERED;
            }
            // Another source may have used up a path this cycle; that is no reason to sleep.
            if (outOfBandwidth) {
                return ItemTransferStatus.BANDWIDTH_USED;
            }
            if (insertRefused) {
                return ItemTransferStatus.DESTINATIONS_FULL;
            }
            return insertFiltered ? ItemTransferStatus.INSERT_FILTERED : ItemTransferStatus.NO_DESTINATION;
        }
    }

    private void transfer(ConduitBlockConnection extractConnection, ItemConduit conduit, IItemHandler extractHandler,
            List<ConduitConnectionPath> insertPaths, ItemConduitNodeData nodeData,
            Object2IntOpenHashMap<ConduitConnectionPath> usedPerPath, Attempt attempt, int cycles) {
        var connectionConfig = extractConnection.connectionConfig(EIOConduitTypes.ConnectionTypes.ITEM.get());

        // Get extraction filter
        var extractFilterStack = extractConnection.inventory().getStackInSlot(ItemConduit.EXTRACT_FILTER_SLOT);
        var extractFilter = extractFilterStack.isEmpty() ? null : extractFilterStack.getCapability(EnderIOCapabilities.ITEM_FILTER);
        var extractContext = extractFilter == null ? null : new ItemFilterContext(extractHandler, ItemFilterDirection.EXTRACT);

        int speed = (int) Math.min(Integer.MAX_VALUE, (long) conduit.transferRatePerCycle() * cycles);
        int tickRate = conduit.networkTickRate();
        int batches = 0;
        int maxBatches = MAX_BATCHES_PER_CYCLE * Math.min(cycles, 8);
        // Balanced delivery gives every destination at most an equal part of this cycle's items.
        var balancedSent = connectionConfig.isBalanced() ? new Object2IntOpenHashMap<ConduitConnectionPath>() : null;
        int share = speed;
        if (balancedSent != null) {
            long destinations = insertPaths.stream()
                    .filter(path -> connectionConfig.isSelfFeed() || path.end().node() != extractConnection.node()
                            || path.end().connectionSide() != extractConnection.connectionSide())
                    .count();
            share = (int) Math.max(1, (speed + destinations - 1) / Math.max(1, destinations));
        }

        nextItem: for (int i = 0; i < extractHandler.getSlots(); i++) {
            // Keep drawing from the same slot: bulk storage holds far more than one stack in it.
            while (attempt.moved < speed && batches < maxBatches) {
                ItemStack extractedItem = extractHandler.extractItem(i, speed - attempt.moved, true);
                if (extractedItem.isEmpty()) {
                    continue nextItem;
                }
                attempt.sawItem = true;

                // Ensure we cap the stack to its max size, even if the parent handler doesn't respect it.
                int extractedItemMaxStack = extractedItem.getMaxStackSize();
                if (extractedItem.getCount() > extractedItemMaxStack) {
                    extractedItem = extractedItem.copyWithCount(extractedItemMaxStack);
                }

                if (extractFilter != null) {
                    extractedItem = extractFilter.test(extractedItem, extractContext);
                    if (extractedItem.isEmpty()) {
                        continue nextItem;
                    }
                }
                attempt.passedExtractFilter = true;

                int startingIndex = 0;
                if (connectionConfig.isRoundRobin()) {
                    startingIndex = nodeData.getIndex(extractConnection.connectionSide());
                    if (insertPaths.size() <= startingIndex) {
                        startingIndex = 0;
                    }
                }

                boolean movedBatch = false;
                for (int j = startingIndex; j < startingIndex + insertPaths.size(); j++) {
                    int senderIndex = j % insertPaths.size();
                    var insertPath = insertPaths.get(senderIndex);
                    var insertConnection = insertPath.end();

                    long capacity = (long) Math.max(0, insertPath.property(ItemConduit.PATH_SPEED_AND_TICK_RATE).getAdjustedSpeed(tickRate)) * cycles;
                    int used = usedPerPath.getInt(insertPath);
                    int remaining = (int) Math.min(Integer.MAX_VALUE, Math.max(0, capacity - used));
                    if (remaining == 0) {
                        attempt.outOfBandwidth = true;
                        continue;
                    }
                    if (balancedSent != null) {
                        remaining = Math.min(remaining, share - balancedSent.getInt(insertPath));
                        if (remaining <= 0) {
                            continue;
                        }
                    }

                    // Prevent self-feeding
                    if (!connectionConfig.isSelfFeed()
                        && extractConnection.connectionSide() == insertConnection.connectionSide()
                        && extractConnection.node() == insertConnection.node()) {
                        continue;
                    }

                    var insertHandler = insertConnection.getSidedCapability(Capabilities.ItemHandler.BLOCK);
                    if (insertHandler == null) {
                        continue;
                    }
                    int offer = Math.min(extractedItem.getCount(), Math.min(remaining, speed - attempt.moved));
                    var filterStack = insertConnection.inventory().getStackInSlot(ItemConduit.INSERT_FILTER_SLOT);
                    var filter = filterStack.isEmpty() ? null : filterStack.getCapability(EnderIOCapabilities.ITEM_FILTER);
                    ItemStack itemToInsert;
                    if (filter != null) {
                        // Custom filters may edit the argument even when they reject it.
                        var candidate = filter instanceof NonMutatingItemFilter && offer == extractedItem.getCount()
                                ? extractedItem : extractedItem.copyWithCount(offer);
                        itemToInsert = filter.test(candidate, new ItemFilterContext(insertHandler, ItemFilterDirection.INSERT));
                        if (itemToInsert.isEmpty()) {
                            attempt.insertFiltered = true;
                            continue;
                        }
                        if (itemToInsert == extractedItem || itemToInsert.getCount() > offer) {
                            itemToInsert = itemToInsert.copyWithCount(Math.min(offer, itemToInsert.getCount()));
                        }
                    } else {
                        itemToInsert = extractedItem.copyWithCount(offer);
                    }

                    // Some handlers modify their input stack while inserting (e.g. Create chutes).
                    int offeredCount = itemToInsert.getCount();
                    ItemStack notInserted = ItemHandlerHelper.insertItem(insertHandler, itemToInsert, false);
                    int successfullyInserted = offeredCount - notInserted.getCount();

                    if (successfullyInserted <= 0) {
                        attempt.insertRefused = true;
                        continue;
                    }

                    attempt.moved += successfullyInserted;
                    batches++;
                    int taken = extractHandler.extractItem(i, successfullyInserted, false).getCount();

                    // Track how much was inserted through this path.
                    usedPerPath.addTo(insertPath, successfullyInserted);
                    if (balancedSent != null) {
                        balancedSent.addTo(insertPath, successfullyInserted);
                    }

                    if (connectionConfig.isRoundRobin()) {
                        nodeData.setIndex(extractConnection.connectionSide(), senderIndex + 1);
                    }

                    // A source that hands over less than it offered must not be asked again this cycle.
                    if (taken < successfullyInserted) {
                        continue nextItem;
                    }
                    movedBatch = true;
                    break;
                }

                if (!movedBatch) {
                    continue nextItem;
                }
            }

            if (attempt.moved >= speed || batches >= maxBatches) {
                return;
            }
        }
    }

}
