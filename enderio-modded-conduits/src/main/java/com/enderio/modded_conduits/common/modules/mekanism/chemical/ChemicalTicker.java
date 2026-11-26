package com.enderio.modded_conduits.common.modules.mekanism.chemical;

import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.path.ConduitConnectionPath;
import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.api.conduits.ticker.ConduitTickerBase;
import com.enderio.modded_conduits.common.modules.mekanism.MekanismModule;
import com.enderio.modded_conduits.common.modules.mekanism.chemical_filter.EnderChemicalFilter;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public class ChemicalTicker extends ConduitTickerBase<ChemicalConduit> {

    public static final ChemicalTicker INSTANCE = new ChemicalTicker();

    private ChemicalTicker() {
        super(MekanismModule.TYPE_CHEMICAL::get);
    }

    @Override
    protected void tickNetwork(ServerLevel level, ConduitNetwork network, List<Holder<Conduit<?, ?>>> tickableConduits) {
        var insertedPerPath = new Object2LongOpenHashMap<ConduitConnectionPath>();

        for (var channel : network.allChannels()) {
            for (var extractConnection : network.extractConnections(channel)) {
                var insertPaths = network.insertConnectionsFrom(extractConnection);
                if (insertPaths.isEmpty()) {
                    continue;
                }

                final var extractConduit = extractConnection.node().conduit(conduitType());
                long remaining = extractConduit.value().transferRatePerTick() * conduitType().getTickRate(extractConduit);
                for (int i = 0; remaining > 0; i++) {
                    var handler = extractConnection.getSidedCapability(MekanismModule.Capabilities.CHEMICAL);
                    if (handler == null || i >= handler.getChemicalTanks()) {
                        break;
                    }
                    ChemicalStack chemical = handler.getChemicalInTank(i);
                    if (!chemical.isEmpty()) {
                        remaining = doChemicalTransfer(chemical, remaining, extractConnection, handler, insertPaths, insertedPerPath);
                    }
                }
            }
        }
    }

    private long doChemicalTransfer(ChemicalStack chemical, long maxTransfer, ConduitBlockConnection extractConnection,
            IChemicalHandler extractHandler, List<ConduitConnectionPath> insertPaths, Object2LongOpenHashMap<ConduitConnectionPath> insertedPerPath) {
        var filterStack = extractConnection.inventory().getStackInSlot(ChemicalConduit.EXTRACT_FILTER_SLOT);
        var extractFilter = filterStack.isEmpty() ? null : filterStack.getCapability(MekanismModule.Capabilities.CHEMICAL_FILTER);
        var simulated = extractHandler.extractChemical(chemical.copyWithAmount(maxTransfer), Action.SIMULATE);
        if (simulated.isEmpty()) {
            return maxTransfer;
        }
        var allowed = extractFilter == null ? simulated : extractFilter.test(extractHandler, simulated);
        long available = Math.min(maxTransfer, allowed.getAmount());

        for (var path : insertPaths) {
            if (available <= 0) {
                break;
            }
            long sent = insertedPerPath.getLong(path);
            long offer = Math.min(available, path.property(ChemicalConduit.PATH_MAX_TRANSFER_RATE) - sent);
            if (offer <= 0) {
                continue;
            }
            var insert = path.end();
            var handler = insert.getSidedCapability(MekanismModule.Capabilities.CHEMICAL);
            if (handler == null) {
                continue;
            }
            var stack = insert.inventory().getStackInSlot(ChemicalConduit.INSERT_FILTER_SLOT);
            var filter = stack.isEmpty() ? null : stack.getCapability(MekanismModule.Capabilities.CHEMICAL_FILTER);
            // The built-in whitelist compares only the resource type/components, independent of amount.
            if (filter instanceof EnderChemicalFilter && filter.test(handler, allowed).isEmpty()) {
                continue;
            }
            var candidate = allowed.copyWithAmount(offer);
            if (filter != null && !(filter instanceof EnderChemicalFilter)) {
                candidate = filter.test(handler, candidate);
                if (candidate.isEmpty()) {
                    continue;
                }
                if (candidate.getAmount() > offer) {
                    candidate = candidate.copyWithAmount(offer);
                }
            }

            // The source may change after a previous destination or a filter callback.
            long transferred = tryChemicalTransfer(handler, extractHandler, candidate, true).getAmount();
            if (transferred > 0) {
                insertedPerPath.put(path, sent + transferred);
                maxTransfer -= transferred;
                available -= transferred;
            }
        }
        return maxTransfer;
    }

    public static ChemicalStack tryChemicalTransfer(IChemicalHandler chemicalDestination,
            IChemicalHandler chemicalSource, int maxAmount, boolean doTransfer) {
        ChemicalStack drainable = chemicalSource.extractChemical(maxAmount, Action.SIMULATE);
        return !drainable.isEmpty()
                ? tryChemicalTransfer_Internal(chemicalDestination, chemicalSource, drainable, doTransfer)
                : ChemicalStack.EMPTY;
    }

    public static ChemicalStack tryChemicalTransfer(IChemicalHandler chemicalDestination,
            IChemicalHandler chemicalSource, ChemicalStack resource, boolean doTransfer) {
        ChemicalStack drainable = chemicalSource.extractChemical(resource, Action.SIMULATE);
        return !drainable.isEmpty() && ChemicalStack.isSameChemical(resource, drainable)
                ? tryChemicalTransfer_Internal(chemicalDestination, chemicalSource, drainable, doTransfer)
                : ChemicalStack.EMPTY;
    }

    private static ChemicalStack tryChemicalTransfer_Internal(IChemicalHandler chemicalDestination,
            IChemicalHandler chemicalSource, ChemicalStack drainable, boolean doTransfer) {
        long fillableAmount = drainable.getAmount()
                - chemicalDestination.insertChemical(drainable, Action.SIMULATE).getAmount();
        if (fillableAmount > 0) {
            drainable.setAmount(fillableAmount);
            if (!doTransfer) {
                return drainable;
            }

            ChemicalStack drained = chemicalSource.extractChemical(drainable, Action.EXECUTE);
            if (!drained.isEmpty()) {
                long remainder = chemicalDestination.insertChemical(drained, Action.EXECUTE).getAmount();
                drained.setAmount(drained.getAmount() - remainder);
                return drained;
            }
        }

        return ChemicalStack.EMPTY;
    }
}
