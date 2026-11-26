package com.enderio.enderio.content.machines.capacitor_bank;

import com.enderio.enderio.api.io.RedstoneControl;
import com.enderio.enderio.foundation.network.packets.ClientBoundSyncCapacitorBankPacket;
import com.enderio.enderio.foundation.network.packets.ClientboundCapacitorBankValuesPacket;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class CapacitorBankSyncTests {
    private CapacitorBankSavedData loaded() {
        return CapacitorBankSavedData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
            [{"energy":30,"redstone":"always_active","nodes":[{"pos":[0,0,0],"capacity":100}],"edges":[]}]
            """)).getOrThrow();
    }

    @Test
    void idleAndSimulatedTransfersStayCleanAndChangesSurviveReload(MinecraftServer server) {
        var saved = loaded();
        var network = saved.claimNode(BlockPos.ZERO).getNetwork();
        saved.setDirty(false);
        network.prepareEnergyToDistribute();
        network.distributeEnergy();
        network.extractEnergy(BlockPos.ZERO, Direction.DOWN, 10, true);
        network.receiveEnergy(BlockPos.ZERO, Direction.DOWN, 0, false);
        assertFalse(saved.isDirty());
        network.extractEnergy(BlockPos.ZERO, Direction.DOWN, 10, false);
        assertTrue(saved.isDirty());
        saved.setDirty(false);
        network.setRedstoneControl(RedstoneControl.NEVER_ACTIVE);
        assertTrue(saved.isDirty());
        var encoded = CapacitorBankSavedData.CODEC.encodeStart(JsonOps.INSTANCE, saved).getOrThrow();
        var restored = CapacitorBankSavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().claimNode(BlockPos.ZERO).getNetwork();
        assertEquals(20, restored.getTotalEnergyStored());
        assertEquals(RedstoneControl.NEVER_ACTIVE, restored.getRedstoneControl());
    }

    @Test
    void fullNetworkSplitsAcrossUnloadedNodesWithoutOverflow(MinecraftServer server) {
        var saved = CapacitorBankSavedData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
            [{"energy":6000000000,"redstone":"always_active","nodes":[
                {"pos":[0,0,0],"capacity":2000000000},{"pos":[1,0,0],"capacity":2000000000},
                {"pos":[2,0,0],"capacity":2000000000}],"edges":[{"first":0,"second":1},{"first":1,"second":2}]}]
            """)).getOrThrow();
        var first = saved.claimNode(BlockPos.ZERO);
        var middle = saved.claimNode(BlockPos.ZERO.east());
        var last = saved.claimNode(BlockPos.ZERO.east(2));
        first.getNetwork().disconnect(first, middle);
        assertEquals(2_000_000_000L, first.getNetwork().getTotalEnergyStored());
        assertEquals(4_000_000_000L, last.getNetwork().getTotalEnergyStored());
        assertEquals(2, last.getNetwork().positions().size());
        assertEquals(1, first.getNetwork().positions().size());
        assertTrue(saved.isDirty());
        last.getNetwork().disconnect(middle, last);
        var networks = Set.of(first.getNetwork(), middle.getNetwork(), last.getNetwork());
        assertEquals(6_000_000_000L, networks.stream().mapToLong(CapacitorBankNetwork::getTotalEnergyStored).sum());
        networks.forEach(net -> assertEquals(net.getTotalMaxEnergyStored(), net.getTotalEnergyStored()));
        first.getNetwork().connect(first, middle);
        middle.getNetwork().connect(middle, last);
        assertEquals(6_000_000_000L, first.getNetwork().getTotalEnergyStored());
        assertEquals(3, first.getNetwork().positions().size());
    }

    @Test
    void breakingChargedBridgeKeepsItsShareInTheItemAndBothPartitions(MinecraftServer server) {
        var saved = CapacitorBankSavedData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
            [{"energy":275,"redstone":"always_active","nodes":[
                {"pos":[0,0,0],"capacity":100},{"pos":[1,0,0],"capacity":100},
                {"pos":[2,0,0],"capacity":100}],"edges":[{"first":0,"second":1},{"first":1,"second":2}]}]
            """)).getOrThrow();
        var left = saved.claimNode(BlockPos.ZERO);
        var middle = saved.claimNode(BlockPos.ZERO.east());
        var right = saved.claimNode(BlockPos.ZERO.east(2));
        var network = middle.getNetwork();
        int item = network.takeEnergyForRemoval(middle);
        network.remove(middle);
        assertEquals(91, item);
        assertEquals(92, left.getNetwork().getTotalEnergyStored());
        assertEquals(92, right.getNetwork().getTotalEnergyStored());
        assertEquals(275, item + left.getNetwork().getTotalEnergyStored() + right.getNetwork().getTotalEnergyStored());
    }

    @Test
    void distributionRetainsTieOrderAsExternalStorageAndTargetsChange(MinecraftServer server) {
        var bank = mock(CapacitorBankBlockEntity.class);
        when(bank.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(bank.tier()).thenReturn(CapacitorTier.BASIC);
        var network = new CapacitorBankNode(bank).getNetwork();
        var a = mock(IEnergyStorage.class);
        var b = mock(IEnergyStorage.class);
        when(a.getMaxEnergyStored()).thenReturn(100);
        when(b.getMaxEnergyStored()).thenReturn(100);
        when(b.getEnergyStored()).thenReturn(50);
        when(a.receiveEnergy(1, false)).thenReturn(1);
        when(b.receiveEnergy(1, false)).thenReturn(1);
        var first = new CapacitorBankBlockEntity.SidedEnergy(a, new CapacitorBankNetwork.SidedPos(BlockPos.ZERO, Direction.WEST));
        var second = new CapacitorBankBlockEntity.SidedEnergy(b, new CapacitorBankNetwork.SidedPos(BlockPos.ZERO, Direction.EAST));
        when(bank.getValidPushTargets()).thenReturn(new LinkedHashSet<>(List.of(first, second)));
        network.prepareEnergyToDistribute();
        network.distributeEnergy();
        verify(bank, never()).getValidPushTargets();
        network.receiveEnergy(BlockPos.ZERO, null, 1, false);
        network.prepareEnergyToDistribute();
        network.distributeEnergy();
        verify(b).receiveEnergy(1, false);
        when(b.getEnergyStored()).thenReturn(0);
        network.receiveEnergy(BlockPos.ZERO, null, 1, false);
        network.prepareEnergyToDistribute();
        network.distributeEnergy();
        verify(a).receiveEnergy(1, false);
        verify(bank).getValidPushTargets();
        when(bank.getValidPushTargets()).thenReturn(Set.of(second));
        network.targetsChanged();
        network.receiveEnergy(BlockPos.ZERO, null, 1, false);
        network.prepareEnergyToDistribute();
        network.distributeEnergy();
        verify(b, times(2)).receiveEnergy(1, false);
        assertEquals(0, network.getTotalEnergyStored());
    }

    @Test
    void aChunkWatcherAndMenuViewerEachReceiveOneSnapshot(MinecraftServer server) {
        var network = loaded().claimNode(BlockPos.ZERO).getNetwork();
        var watcher = mock(ServerPlayer.class);
        var menuViewer = mock(ServerPlayer.class);
        var outside = mock(ServerPlayer.class);
        var chunkMap = mock(ChunkMap.class);
        var menu = mock(CapacitorBankMenu.class);
        var bank = mock(CapacitorBankBlockEntity.class);
        when(bank.belongsTo(network)).thenReturn(true);
        when(menu.getBlockEntity()).thenReturn(bank);
        menuViewer.containerMenu = menu;
        watcher.containerMenu = menu;
        when(chunkMap.getPlayers(new ChunkPos(BlockPos.ZERO), false)).thenReturn(List.of(watcher));
        assertEquals(Set.of(watcher, menuViewer), network.findViewers(List.of(outside, menuViewer, watcher), chunkMap));
    }

    @Test
    void firstViewAndReentryReceiveTopologyAndChangedValuesUseNegotiatedPayload(MinecraftServer server) {
        var network = loaded().claimNode(BlockPos.ZERO).getNetwork();
        var modern = mock(ServerPlayer.class);
        var legacy = mock(ServerPlayer.class);
        modern.connection = mock(ServerGamePacketListenerImpl.class);
        legacy.connection = mock(ServerGamePacketListenerImpl.class);
        var both = Set.of(modern, legacy);
        var id = network.getUuid();
        var positions = List.of(BlockPos.ZERO);
        try (var packets = mockStatic(PacketDistributor.class); var channels = mockStatic(NetworkRegistry.class)) {
            channels.when(() -> NetworkRegistry.hasChannel(modern.connection, ClientboundCapacitorBankValuesPacket.TYPE.id())).thenReturn(true);
            network.syncViewers(both);
            var initial = new ClientBoundSyncCapacitorBankPacket(id, 30, 100, 0, 0, positions);
            packets.verify(() -> PacketDistributor.sendToPlayer(modern, initial));
            packets.verify(() -> PacketDistributor.sendToPlayer(legacy, initial));
            packets.clearInvocations();
            network.syncViewers(both);
            packets.verifyNoInteractions();

            network.receiveEnergy(BlockPos.ZERO, Direction.UP, 10, false);
            network.syncViewers(both);
            var delta = new ClientboundCapacitorBankValuesPacket(id, 40, 100, 1, 0);
            packets.verify(() -> PacketDistributor.sendToPlayer(modern, delta));
            packets.verify(() -> PacketDistributor.sendToPlayer(legacy, new ClientBoundSyncCapacitorBankPacket(id, 40, 100, 1, 0, positions)));
            packets.clearInvocations();
            network.forgetViewer(modern);
            packets.clearInvocations();
            network.syncViewers(both);
            packets.verify(() -> PacketDistributor.sendToPlayer(modern, new ClientBoundSyncCapacitorBankPacket(id, 40, 100, 1, 0, positions)));
            packets.verifyNoMoreInteractions();

            var buffer = Unpooled.buffer();
            try {
                ClientboundCapacitorBankValuesPacket.STREAM_CODEC.encode(buffer, delta);
                assertEquals(delta, ClientboundCapacitorBankValuesPacket.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
            CapacitorBankManager.addData(id, 30, 100, 0, 0, positions);
            CapacitorBankManager.updateValues(delta);
            assertSame(positions, CapacitorBankManager.getData(id).nodes());
            assertEquals(40, CapacitorBankManager.getData(id).storedEnergy());
            CapacitorBankManager.removeData(id);
            CapacitorBankManager.updateValues(delta);
            assertNull(CapacitorBankManager.getData(id));
        }
    }
}
