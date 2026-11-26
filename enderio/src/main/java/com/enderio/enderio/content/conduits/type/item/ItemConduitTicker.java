package com.enderio.enderio.content.conduits.type.item;

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

    @Override
    protected void tickNetwork(ServerLevel level, ConduitNetwork network, List<Holder<Conduit<?, ?>>> tickableConduits) {
        var remainingPerPath = new Object2IntOpenHashMap<ConduitConnectionPath>();
        remainingPerPath.defaultReturnValue(-1);

        for (var channel : network.allChannels()) {
            tickChannel(network, tickableConduits, remainingPerPath, channel);
        }
    }

    private void tickChannel(ConduitNetwork network, List<Holder<Conduit<?, ?>>> tickableConduits, Object2IntOpenHashMap<ConduitConnectionPath> remainingPerPath, DyeColor channel) {
        toNextExtract: for (var extractConnection : network.extractConnections(channel)) {
            var insertPaths = network.insertConnectionsFrom(extractConnection);
            if (insertPaths.isEmpty()) {
                continue;
            }

            var extractConduit = extractConnection.node().conduit(conduitType());

            // If this conduit isn't allowed to tick, skip it.
            if (!tickableConduits.contains(extractConduit)) {
                continue;
            }

            // Get extract handler from the connection.
            IItemHandler extractHandler = extractConnection.getSidedCapability(Capabilities.ItemHandler.BLOCK);
            if (extractHandler == null) {
                continue;
            }

            // Get node data for round robin index and connection config
            var nodeData = extractConnection.node().getOrCreateNodeData(EIOConduitTypes.NodeData.ITEM.get());
            var connectionConfig = extractConnection.connectionConfig(EIOConduitTypes.ConnectionTypes.ITEM.get());

            // Get extraction filter
            var extractFilterStack = extractConnection.inventory().getStackInSlot(ItemConduit.EXTRACT_FILTER_SLOT);
            var extractFilter = extractFilterStack.isEmpty() ? null : extractFilterStack.getCapability(EnderIOCapabilities.ITEM_FILTER);
            var extractContext = extractFilter == null ? null : new ItemFilterContext(extractHandler, ItemFilterDirection.EXTRACT);

            int extracted = 0;
            int speed = extractConduit.value().transferRatePerCycle();
            int tickRate = extractConduit.value().networkTickRate();

            nextItem: for (int i = 0; i < extractHandler.getSlots(); i++) {
                ItemStack extractedItem = extractHandler.extractItem(i, speed - extracted, true);
                if (extractedItem.isEmpty()) {
                    continue;
                }

                // Ensure we cap the stack to its max size, even if the parent handler doesn't respect it.
                int extractedItemMaxStack = extractedItem.getMaxStackSize();
                if (extractedItem.getCount() > extractedItemMaxStack) {
                    extractedItem = extractedItem.copyWithCount(extractedItemMaxStack);
                }

                if (extractFilter != null) {
                    extractedItem = extractFilter.test(extractedItem, extractContext);
                    if (extractedItem.isEmpty()) {
                        continue;
                    }
                }

                int startingIndex = 0;
                if (connectionConfig.isRoundRobin()) {
                    startingIndex = nodeData.getIndex(extractConnection.connectionSide());
                    if (insertPaths.size() <= startingIndex) {
                        startingIndex = 0;
                    }
                }

                for (int j = startingIndex; j < startingIndex + insertPaths.size(); j++) {
                    int senderIndex = j % insertPaths.size();
                    var insertPath = insertPaths.get(senderIndex);
                    var insertConnection = insertPath.end();

                    int remaining = remainingPerPath.getInt(insertPath);
                    if (remaining < 0) {
                        remaining = Math.max(0, insertPath.property(ItemConduit.PATH_SPEED_AND_TICK_RATE).getAdjustedSpeed(tickRate));
                        remainingPerPath.put(insertPath, remaining);
                    }
                    if (remaining == 0) {
                        continue;
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
                    int offer = Math.min(extractedItem.getCount(), Math.min(remaining, speed - extracted));
                    var filterStack = insertConnection.inventory().getStackInSlot(ItemConduit.INSERT_FILTER_SLOT);
                    var filter = filterStack.isEmpty() ? null : filterStack.getCapability(EnderIOCapabilities.ITEM_FILTER);
                    ItemStack itemToInsert;
                    if (filter != null) {
                        // Custom filters may edit the argument even when they reject it.
                        var candidate = filter instanceof NonMutatingItemFilter && offer == extractedItem.getCount()
                                ? extractedItem : extractedItem.copyWithCount(offer);
                        itemToInsert = filter.test(candidate, new ItemFilterContext(insertHandler, ItemFilterDirection.INSERT));
                        if (itemToInsert.isEmpty()) {
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

                    if (successfullyInserted > 0) {
                        extracted += successfullyInserted;
                        extractHandler.extractItem(i, successfullyInserted, false);

                        // Track how much was inserted through this path.
                        remainingPerPath.put(insertPath, remaining - successfullyInserted);

                        if (connectionConfig.isRoundRobin()) {
                            nodeData.setIndex(extractConnection.connectionSide(), senderIndex + 1);
                        }

                        if (extracted >= speed) {
                            continue toNextExtract;
                        }
                        continue nextItem;
                    }
                }
            }
        }
    }

}
