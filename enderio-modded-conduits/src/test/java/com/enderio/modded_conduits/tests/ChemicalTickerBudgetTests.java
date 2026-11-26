package com.enderio.modded_conduits.tests;

import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.path.ConduitConnectionPath;
import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.api.conduits.network.node.ConduitNode;
import com.enderio.modded_conduits.common.modules.mekanism.MekanismModule;
import com.enderio.modded_conduits.common.modules.mekanism.chemical.ChemicalConduit;
import com.enderio.modded_conduits.common.modules.mekanism.chemical.ChemicalTicker;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.BasicChemicalTank;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ChemicalTickerBudgetTests {
    @Test
    void partialFirstDestinationLeavesOnlyTheUnspentSourceBudgetForTheLast() {
        long rate = 100;
        var type = MekanismModule.TYPE_CHEMICAL.get();
        var conduit = Holder.direct(new ChemicalConduit(ResourceLocation.withDefaultNamespace("test"), Component.empty(), rate));
        long budget = rate * type.getTickRate(conduit);
        var source = (BasicChemicalTank) BasicChemicalTank.create(budget * 2, null);
        var oxygen = MekanismAPI.CHEMICAL_REGISTRY.get(ResourceLocation.fromNamespaceAndPath("mekanism", "oxygen"));
        source.setStack(new ChemicalStack(oxygen, budget * 2));
        var extract = connection(source);
        when(extract.node().conduit(type)).thenReturn(conduit);

        var destinations = new ArrayList<BasicChemicalTank>();
        var paths = new ArrayList<ConduitConnectionPath>();
        for (int i = 0; i <= budget / rate; i++) {
            var target = (BasicChemicalTank) BasicChemicalTank.create(i == 0 ? rate / 2 : rate, null);
            destinations.add(target);
            paths.add(new ConduitConnectionPath(extract, connection(target), 1, Map.of(ChemicalConduit.PATH_MAX_TRANSFER_RATE, rate)));
        }

        var network = mock(ConduitNetwork.class);
        doReturn(type).when(network).conduitType();
        doReturn(List.of(conduit)).when(network).getTickableConduits(0L, 0);
        when(network.allChannels()).thenReturn(Set.of(DyeColor.GREEN));
        when(network.extractConnections(DyeColor.GREEN)).thenReturn(List.of(extract));
        when(network.insertConnectionsFrom(extract)).thenReturn(paths);
        ChemicalTicker.INSTANCE.tick(mock(ServerLevel.class), network, 0);

        assertEquals(budget, source.getStored());
        assertEquals(budget, destinations.stream().mapToLong(BasicChemicalTank::getStored).sum());
        assertEquals(rate / 2, destinations.getLast().getStored());
    }

    private static ConduitBlockConnection connection(IChemicalHandler tank) {
        var node = mock(ConduitNode.class);
        when(node.getCapability(MekanismModule.Capabilities.CHEMICAL, Direction.NORTH, Direction.SOUTH)).thenReturn(tank);
        when(node.getInventory(Direction.NORTH)).thenReturn(new ItemStackHandler(2));
        return new ConduitBlockConnection(node, Direction.NORTH);
    }
}
