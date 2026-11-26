package com.enderio.enderio.tests.conduits;

import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.path.ConduitConnectionPath;
import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.api.conduits.network.node.ConduitNode;
import com.enderio.enderio.content.conduits.type.energy.EnergyConduit;
import com.enderio.enderio.content.conduits.type.energy.EnergyConduitConnectionConfig;
import com.enderio.enderio.content.conduits.type.energy.EnergyConduitStorage;
import com.enderio.enderio.init.EIOConduitTypes;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EnergyConduitStorageTests {
    @Test
    void retainedCapabilityUsesCurrentNetworkAndStopsOnDetach() {
        var node = mock(ConduitNode.class);
        when(node.isLoaded()).thenReturn(true);
        when(node.getConnectionConfig(Direction.NORTH, EnergyConduitConnectionConfig.TYPE))
                .thenReturn(EnergyConduitConnectionConfig.DEFAULT);
        var type = EIOConduitTypes.ENERGY.get();
        when(node.conduit(type)).thenReturn(Holder.direct(
                new EnergyConduit(ResourceLocation.withDefaultNamespace("test"), Component.empty(), 50)));
        var start = new ConduitBlockConnection(node, Direction.NORTH);
        var storage = new EnergyConduitStorage(Direction.NORTH, node);

        var oldTarget = new EnergyStorage(100);
        var oldNetwork = network(start, oldTarget);
        when(node.getNetwork()).thenReturn(oldNetwork);
        assertEquals(25, storage.receiveEnergy(25, true));
        assertEquals(0, oldTarget.getEnergyStored());
        assertEquals(25, storage.receiveEnergy(25, false));
        assertEquals(25, oldTarget.getEnergyStored());

        var newTarget = new EnergyStorage(100);
        var newNetwork = network(start, newTarget);
        when(node.getNetwork()).thenReturn(newNetwork);
        assertEquals(35, storage.receiveEnergy(60, false));
        assertEquals(35, newTarget.getEnergyStored());
        assertEquals(25, oldTarget.getEnergyStored());
        when(node.isLoaded()).thenReturn(false);
        assertEquals(0, storage.receiveEnergy(10, false));
        assertEquals(35, newTarget.getEnergyStored());
    }

    private static ConduitNetwork network(ConduitBlockConnection start, EnergyStorage target) {
        var node = mock(ConduitNode.class);
        when(node.isLoaded()).thenReturn(true);
        when(node.getCapability(Capabilities.EnergyStorage.BLOCK, Direction.SOUTH, Direction.NORTH)).thenReturn(target);
        var end = new ConduitBlockConnection(node, Direction.SOUTH);
        var path = new ConduitConnectionPath(start, end, 1, Map.of(EnergyConduit.PATH_MAX_TRANSFER_RATE, 35));
        var network = mock(ConduitNetwork.class);
        when(network.insertConnectionsFrom(start)).thenReturn(List.of(path));
        return network;
    }
}
