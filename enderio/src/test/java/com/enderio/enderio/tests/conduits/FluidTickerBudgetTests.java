package com.enderio.enderio.tests.conduits;

import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.path.ConduitConnectionPath;
import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.api.conduits.network.node.ConduitNode;
import com.enderio.enderio.content.conduits.type.fluid.FluidConduit;
import com.enderio.enderio.content.conduits.type.fluid.FluidConduitTicker;
import com.enderio.enderio.init.EIOConduitTypes;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class FluidTickerBudgetTests {
    @Test
    void partialFirstDestinationLeavesOnlyTheUnspentSourceBudgetForTheLast() {
        int rate = 100;
        var type = EIOConduitTypes.FLUID.get();
        var conduit = Holder.direct(new FluidConduit(ResourceLocation.withDefaultNamespace("test"), Component.empty(), rate));
        int budget = rate * type.getTickRate(conduit);
        var source = new FluidTank(budget * 2);
        source.setFluid(new FluidStack(Fluids.WATER, budget * 2));
        var extract = connection(source);
        when(extract.node().conduit(type)).thenReturn(conduit);

        var destinations = new ArrayList<FluidTank>();
        var paths = new ArrayList<ConduitConnectionPath>();
        for (int i = 0; i <= budget / rate; i++) {
            var target = new FluidTank(i == 0 ? rate / 2 : rate);
            destinations.add(target);
            paths.add(new ConduitConnectionPath(extract, connection(target), 1, Map.of(FluidConduit.PATH_MAX_TRANSFER_RATE, rate)));
        }

        var network = mock(ConduitNetwork.class);
        doReturn(type).when(network).conduitType();
        doReturn(List.of(conduit)).when(network).getTickableConduits(0L, 0);
        when(network.allChannels()).thenReturn(Set.of(DyeColor.GREEN));
        when(network.extractConnections(DyeColor.GREEN)).thenReturn(List.of(extract));
        when(network.insertConnectionsFrom(extract)).thenReturn(paths);
        FluidConduitTicker.INSTANCE.tick(mock(ServerLevel.class), network, 0);

        assertEquals(budget, source.getFluidAmount());
        assertEquals(budget, destinations.stream().mapToInt(FluidTank::getFluidAmount).sum());
        assertEquals(rate / 2, destinations.getLast().getFluidAmount());
    }

    private static ConduitBlockConnection connection(IFluidHandler tank) {
        var node = mock(ConduitNode.class);
        when(node.getCapability(Capabilities.FluidHandler.BLOCK, Direction.NORTH, Direction.SOUTH)).thenReturn(tank);
        when(node.getInventory(Direction.NORTH)).thenReturn(new ItemStackHandler(2));
        return new ConduitBlockConnection(node, Direction.NORTH);
    }
}
