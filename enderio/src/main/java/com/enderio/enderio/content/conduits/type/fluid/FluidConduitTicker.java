package com.enderio.enderio.content.conduits.type.fluid;

import com.enderio.enderio.api.EnderIOCapabilities;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.path.ConduitConnectionPath;
import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.api.conduits.ticker.ConduitTickerBase;
import com.enderio.enderio.init.EIOConduitTypes;
import com.enderio.enderio.content.filters.fluid.EnderFluidFilter;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;

public class FluidConduitTicker extends ConduitTickerBase<FluidConduit> {

    public static final FluidConduitTicker INSTANCE = new FluidConduitTicker();

    private FluidConduitTicker() {
        super(EIOConduitTypes.FLUID::get);
    }

    @Override
    protected void tickNetwork(ServerLevel level, ConduitNetwork network, List<Holder<Conduit<?, ?>>> tickableConduits) {
        var insertedPerPath = new Object2IntOpenHashMap<ConduitConnectionPath>();

        for (var channel : network.allChannels()) {
            for (var extractConnection : network.extractConnections(channel)) {
                var insertPaths = network.insertConnectionsFrom(extractConnection);
                if (insertPaths.isEmpty()) {
                    continue;
                }

                final var extractConduit = extractConnection.node().conduit(conduitType());
                int remaining = extractConduit.value().transferRatePerTick() * conduitType().getTickRate(extractConduit);
                for (int i = 0; remaining > 0; i++) {
                    var handler = extractConnection.getSidedCapability(Capabilities.FluidHandler.BLOCK);
                    if (handler == null || i >= handler.getTanks()) {
                        break;
                    }
                    FluidStack fluid = handler.getFluidInTank(i);
                    if (!fluid.isEmpty()) {
                        remaining = doFluidTransfer(fluid, remaining, extractConnection, handler, insertPaths, insertedPerPath);
                    }
                }
            }
        }
    }

    private int doFluidTransfer(FluidStack fluid, int maxTransfer, ConduitBlockConnection extractConnection,
            IFluidHandler extractHandler, List<ConduitConnectionPath> insertPaths, Object2IntOpenHashMap<ConduitConnectionPath> insertedPerPath) {
        var filterStack = extractConnection.inventory().getStackInSlot(FluidConduit.EXTRACT_FILTER_SLOT);
        var extractFilter = filterStack.isEmpty() ? null : filterStack.getCapability(EnderIOCapabilities.FLUID_FILTER);
        var simulated = extractHandler.drain(fluid.copyWithAmount(maxTransfer), IFluidHandler.FluidAction.SIMULATE);
        if (simulated.isEmpty()) {
            return maxTransfer;
        }
        var allowed = extractFilter == null ? simulated : extractFilter.test(extractHandler, simulated);
        int available = Math.min(maxTransfer, allowed.getAmount());

        for (var path : insertPaths) {
            if (available <= 0) {
                break;
            }
            int sent = insertedPerPath.getInt(path);
            int offer = Math.min(available, path.property(FluidConduit.PATH_MAX_TRANSFER_RATE) - sent);
            if (offer <= 0) {
                continue;
            }
            var insert = path.end();
            var handler = insert.getSidedCapability(Capabilities.FluidHandler.BLOCK);
            if (handler == null) {
                continue;
            }
            var stack = insert.inventory().getStackInSlot(FluidConduit.INSERT_FILTER_SLOT);
            var filter = stack.isEmpty() ? null : stack.getCapability(EnderIOCapabilities.FLUID_FILTER);
            // The built-in whitelist compares only the resource type/components, independent of amount.
            if (filter instanceof EnderFluidFilter && filter.test(handler, allowed).isEmpty()) {
                continue;
            }
            var candidate = allowed.copyWithAmount(offer);
            if (filter != null && !(filter instanceof EnderFluidFilter)) {
                candidate = filter.test(handler, candidate);
                if (candidate.isEmpty()) {
                    continue;
                }
                if (candidate.getAmount() > offer) {
                    candidate = candidate.copyWithAmount(offer);
                }
            }

            // The source may change after a previous destination or a filter callback.
            int transferred = FluidUtil.tryFluidTransfer(handler, extractHandler, candidate, true).getAmount();
            if (transferred > 0) {
                insertedPerPath.put(path, sent + transferred);
                maxTransfer -= transferred;
                available -= transferred;
            }
        }
        return maxTransfer;
    }
}
