package com.enderio.enderio.tests.conduits;

import com.enderio.core.common.network.NetworkDataSlot;
import com.enderio.enderio.api.EnderIORegistries;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.connection.ConnectionStatus;
import com.enderio.enderio.api.conduits.connection.config.ConnectionConfig;
import com.enderio.enderio.api.conduits.network.node.NodeData;
import com.enderio.enderio.content.conduits.ConduitSorter;
import com.enderio.enderio.content.conduits.bundle.ConduitBundleBlockEntity;
import com.enderio.enderio.content.conduits.bundle.ConduitShape;
import com.enderio.enderio.content.conduits.network.ConduitNetworkSavedData;
import com.enderio.enderio.content.conduits.type.item.ItemConduitConnectionConfig;
import com.enderio.enderio.content.conduits.type.item.ItemConduitNodeData;
import com.enderio.enderio.init.EIOBlocks;
import com.enderio.enderio.init.EIOConduits;
import com.enderio.enderio.init.EIOItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(EphemeralTestServerProvider.class)
public class ConduitBundleLoadTests {
    private static ServerLevel serverLevel(MinecraftServer server) {
        // The ephemeral server supplies registries but does not create dimensions.
        // Mock the world boundary; keep the saved data, nodes and networks real.
        var level = mock(ServerLevel.class);
        var storage = mock(DimensionDataStorage.class);
        when(level.registryAccess()).thenReturn(server.registryAccess());
        when(level.getDataStorage()).thenReturn(storage);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        doReturn(new ConduitNetworkSavedData()).when(storage)
            .computeIfAbsent(any(), eq("enderio_conduit_network"));
        return level;
    }

    private static Holder<Conduit<?, ?>> itemConduit(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(EnderIORegistries.Keys.CONDUIT)
            .getOrThrow(EIOConduits.ITEM);
    }

    private static CompoundTag savedBundle(MinecraftServer server, Holder<Conduit<?, ?>> conduit) {
        var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var tag = new CompoundTag();
        var conduits = new ListTag();
        conduits.add(Conduit.CODEC.encodeStart(ops, conduit).getOrThrow());
        tag.put("Conduits", conduits);

        var node = new CompoundTag();
        node.put("Conduit", Conduit.CODEC.encodeStart(ops, conduit).getOrThrow());
        var data = new ItemConduitNodeData();
        data.setIndex(Direction.NORTH, 7);
        node.put("Data", NodeData.GENERIC_CODEC
            .encodeStart(ops, data).getOrThrow());
        var nodes = new ListTag();
        nodes.add(node);
        tag.put("NodeData", nodes);

        var north = new CompoundTag();
        north.putString("Side", Direction.NORTH.getName());
        north.putString("Status", ConnectionStatus.CONNECTED_BLOCK.getSerializedName());
        north.put("Config", ConnectionConfig.GENERIC_CODEC.encodeStart(ops,
            ItemConduitConnectionConfig.DEFAULT).getOrThrow());
        var inventory = new ListTag();
        inventory.add(new ItemStack(EIOItems.BASIC_ITEM_FILTER.get()).save(server.registryAccess()));
        north.put("Inventory", inventory);
        var sides = new ListTag();
        sides.add(north);
        var connections = new ListTag();
        connections.add(sides);
        tag.put("Connections", connections);
        return tag;
    }

    @Test
    void nbtAppliedAfterSetLevelRestoresNodesBeforeConnectionUpdates(MinecraftServer server) {
        var conduit = itemConduit(server);
        var bundle = new ConduitBundleBlockEntity(new BlockPos(32, 100, 32),
            EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState());
        bundle.setLevel(serverLevel(server));
        bundle.loadWithComponents(savedBundle(server, conduit), server.registryAccess());

        Assertions.assertDoesNotThrow(() -> bundle.onConnectionsUpdated(conduit));
        var node = bundle.getConduitNode(conduit);
        Assertions.assertNotNull(node);
        Assertions.assertNotNull(node.getNetwork());
        Assertions.assertEquals(7, node.getNodeData(ItemConduitNodeData.TYPE).getIndex(Direction.NORTH));
        Assertions.assertEquals(ItemConduitConnectionConfig.DEFAULT,
            bundle.getConnectionConfig(conduit, Direction.NORTH));
        Assertions.assertEquals(ConnectionStatus.CONNECTED_BLOCK,
            bundle.getConnectionStatus(conduit, Direction.NORTH));
        var inventory = bundle.getConnectionInventory(conduit, Direction.NORTH);
        Assertions.assertNotNull(inventory);
        Assertions.assertTrue(inventory.getStackInSlot(0).is(EIOItems.BASIC_ITEM_FILTER.get()));
        Assertions.assertEquals(1, inventory.getStackInSlot(0).getCount());
        Assertions.assertDoesNotThrow(() -> bundle.setConnectionStatus(conduit, Direction.NORTH,
            ConnectionStatus.DISCONNECTED));
        Assertions.assertDoesNotThrow(() -> bundle.onConnectionsUpdated(conduit));
    }

    @Test
    void normalLoadOrderStillRestoresNodes(MinecraftServer server) {
        var conduit = itemConduit(server);
        var bundle = new ConduitBundleBlockEntity(new BlockPos(64, 100, 32),
            EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState());
        bundle.loadWithComponents(savedBundle(server, conduit), server.registryAccess());
        bundle.setLevel(serverLevel(server));

        Assertions.assertNotNull(bundle.getConduitNode(conduit));
        Assertions.assertEquals(7, bundle.getConduitNode(conduit)
            .getNodeData(ItemConduitNodeData.TYPE).getIndex(Direction.NORTH));
        Assertions.assertDoesNotThrow(() -> bundle.onConnectionsUpdated(conduit));
    }

    @Test
    void repeatedNbtLoadDoesNotReplaceAnAttachedNode(MinecraftServer server) {
        var conduit = itemConduit(server);
        var bundle = new ConduitBundleBlockEntity(new BlockPos(96, 100, 32),
            EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState());
        bundle.loadWithComponents(savedBundle(server, conduit), server.registryAccess());
        bundle.setLevel(serverLevel(server));
        var node = bundle.getConduitNode(conduit);
        var network = node.getNetwork();
        var saved = bundle.saveWithoutMetadata(server.registryAccess());

        bundle.loadWithComponents(saved, server.registryAccess());

        Assertions.assertSame(node, bundle.getConduitNode(conduit));
        Assertions.assertSame(network, node.getNetwork());
        Assertions.assertEquals(7, node.getNodeData(ItemConduitNodeData.TYPE).getIndex(Direction.NORTH));
    }

    @Test
    void connectedConduitsRespectDisplayOrderRegardlessOfSaveOrder(MinecraftServer server) {
        var item = itemConduit(server);
        var fluid = server.registryAccess().lookupOrThrow(EnderIORegistries.Keys.CONDUIT).getOrThrow(EIOConduits.FLUID);
        var tag = savedBundle(server, item);
        var conduits = tag.getList("Conduits", Tag.TAG_STRING);
        conduits.add(Conduit.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), fluid).getOrThrow());
        var north = new CompoundTag();
        north.putString("Side", Direction.NORTH.getName());
        north.putString("Status", ConnectionStatus.CONNECTED_CONDUIT.getSerializedName());
        var sides = new ListTag();
        sides.add(north);
        tag.getList("Connections", Tag.TAG_LIST).add(sides);

        var bundle = new ConduitBundleBlockEntity(BlockPos.ZERO, EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState());
        bundle.loadWithComponents(tag, server.registryAccess());
        try (var sorter = mockStatic(ConduitSorter.class)) {
            sorter.when(() -> ConduitSorter.getSortIndex(item)).thenReturn(1);
            sorter.when(() -> ConduitSorter.getSortIndex(fluid)).thenReturn(0);
            Assertions.assertEquals(List.of(fluid, item), bundle.getConnectedConduits(Direction.NORTH));
            Assertions.assertEquals(List.of(item, fluid), bundle.getConduits());
            Assertions.assertTrue(bundle.getConnectedConduits(Direction.SOUTH).isEmpty());
        }
    }

    @Test
    void highlightReusesAHitUntilTheConduitShapeChanges(MinecraftServer server) {
        var item = itemConduit(server);
        var tag = savedBundle(server, item);
        var bundle = new ConduitBundleBlockEntity(BlockPos.ZERO, EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState());
        bundle.loadWithComponents(tag, server.registryAccess());
        var shape = spy(new ConduitShape());
        shape.updateConduit(bundle);
        var hit = new BlockHitResult(new Vec3(0.5, 0.5, 0.01), Direction.NORTH, BlockPos.ZERO, false);

        var first = shape.getShapeFromHit(BlockPos.ZERO, hit);
        Assertions.assertFalse(first.isEmpty());
        Assertions.assertSame(first, shape.getShapeFromHit(BlockPos.ZERO, hit));
        verify(shape, times(1)).getConduit(BlockPos.ZERO, hit);
        Assertions.assertTrue(shape.getShapeFromHit(BlockPos.ZERO.above(), hit).isEmpty());
        Assertions.assertSame(first, shape.getShapeFromHit(BlockPos.ZERO, hit));

        tag.getList("Connections", Tag.TAG_LIST).getList(0).getCompound(0).putString("Side", Direction.SOUTH.getName());
        bundle.loadWithComponents(tag, server.registryAccess());
        shape.updateConduit(bundle);
        Assertions.assertTrue(shape.getShapeFromHit(BlockPos.ZERO, hit).isEmpty());
    }

    @Test
    void connectorHitsMatchTheConnectedFaceInAllRotations(MinecraftServer server) {
        var item = itemConduit(server);
        var bundle = new ConduitBundleBlockEntity(BlockPos.ZERO, EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState());
        var tag = savedBundle(server, item);
        for (var direction : Direction.values()) {
            tag.getList("Connections", Tag.TAG_LIST).getList(0).getCompound(0).putString("Side", direction.getName());
            bundle.loadWithComponents(tag, server.registryAccess());
            var shape = bundle.getShape();
            shape.updateConduit(bundle);

            var center = Vec3.atCenterOf(BlockPos.ZERO);
            var offset = Vec3.atLowerCornerOf(direction.getNormal()).scale(0.49);
            var hit = new BlockHitResult(center.add(offset), direction, BlockPos.ZERO, false);
            var connection = shape.getConnectionFromHit(BlockPos.ZERO, hit);
            Assertions.assertNotNull(connection);
            Assertions.assertEquals(direction, connection.getFirst());
            Assertions.assertEquals(item, connection.getSecond());
            Assertions.assertEquals(hit.getLocation(), shape.getTotalShape().closestPointTo(hit.getLocation()).orElseThrow());
            var opposite = center.subtract(offset);
            Assertions.assertNotEquals(opposite, shape.getTotalShape().closestPointTo(opposite).orElseThrow());
        }
    }

    @Test
    void repeatedLoadsRestartTheTwoTickConnectionDelay(MinecraftServer server) {
        var level = serverLevel(server);
        var pos = new BlockPos(7, 80, 7);
        var bundle = spy(new ConduitBundleBlockEntity(pos, EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState()));
        bundle.setLevel(level);
        var tag = new CompoundTag();
        bundle.loadWithComponents(tag, server.registryAccess());
        bundle.serverTick();
        bundle.loadWithComponents(tag, server.registryAccess());
        bundle.serverTick();
        verify(bundle, never()).updateConnections(level, pos, null, false);

        bundle.serverTick();
        bundle.serverTick();
        verify(bundle).updateConnections(level, pos, null, false);
    }

    @Test
    void dirtySyncIsCoalescedAndDoesNotReachTheReplacementBundle(MinecraftServer server) {
        var level = serverLevel(server);
        var pos = new BlockPos(5, 80, 5);
        var state = EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState();
        var old = new ConduitBundleBlockEntity(pos, state);
        old.setLevel(level);
        old.markNodesDirty();
        old.markNodesDirty();
        old.serverTick();
        old.serverTick();
        verify(level).sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);

        old.markNodesDirty();
        old.onChunkUnloaded();
        old.setRemoved();
        var replacement = new ConduitBundleBlockEntity(pos, state);
        replacement.setLevel(level);
        var ticker = state.getTicker(level, replacement.getType());
        Assertions.assertNotNull(ticker);
        replacement.serverTick();
        verify(level, times(1)).sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    @Test
    void aDisconnectedSideWithFilterInventorySurvivesSparseSerialization(MinecraftServer server) {
        var item = itemConduit(server);
        var source = savedBundle(server, item);
        source.getList("Connections", Tag.TAG_LIST).getList(0).getCompound(0)
            .putString("Status", ConnectionStatus.DISCONNECTED.getSerializedName());
        var state = EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState();
        var original = new ConduitBundleBlockEntity(BlockPos.ZERO, state);
        original.loadWithComponents(source, server.registryAccess());
        var saved = original.getUpdateTag(server.registryAccess());
        Assertions.assertEquals(1, saved.getList("Connections", Tag.TAG_LIST).getList(0).size());

        var copy = new ConduitBundleBlockEntity(BlockPos.ZERO, state);
        copy.loadWithComponents(saved, server.registryAccess());
        Assertions.assertEquals(ConnectionStatus.DISCONNECTED, copy.getConnectionStatus(item, Direction.NORTH));
        Assertions.assertTrue(copy.getConnectionInventory(item, Direction.NORTH).getStackInSlot(0).is(EIOItems.BASIC_ITEM_FILTER.get()));
        Assertions.assertEquals(ConnectionStatus.DISCONNECTED, copy.getConnectionStatus(item, Direction.SOUTH));
    }

    @Test
    void dirtyDuringNotificationIsSentOnTheNextTick(MinecraftServer server) {
        var level = serverLevel(server);
        var pos = new BlockPos(8, 80, 8);
        var state = EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState();
        var bundle = new ConduitBundleBlockEntity(pos, state);
        bundle.setLevel(level);
        doAnswer(invocation -> {
            bundle.markNodesDirty();
            return null;
        }).doNothing().when(level).sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
        bundle.markNodesDirty();
        bundle.serverTick();
        bundle.serverTick();
        bundle.serverTick();
        verify(level, times(2)).sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    @Test
    void idleServerTickerPreservesTheFiveTickSlotPhase(MinecraftServer server) {
        var level = serverLevel(server);
        var pos = new BlockPos(8, 80, 8);
        var state = EIOBlocks.CONDUIT_BUNDLE.get().defaultBlockState();
        var bundle = new ConduitBundleBlockEntity(pos, state);
        bundle.setLevel(level);
        bundle.serverTick();
        var slot = mock(NetworkDataSlot.class);
        bundle.addDataSlot(slot);
        for (int i = 0; i < 4; i++) {
            bundle.serverTick();
        }
        verify(slot, never()).doesNeedUpdate();
        bundle.serverTick();
        verify(slot).doesNeedUpdate();
        when(level.isClientSide()).thenReturn(true);
        Assertions.assertNull(state.getTicker(level, bundle.getType()));
    }

}
