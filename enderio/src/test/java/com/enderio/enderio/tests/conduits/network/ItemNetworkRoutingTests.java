package com.enderio.enderio.tests.conduits.network;

import com.enderio.enderio.api.EnderIORegistries;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.connection.ConduitBlockConnection;
import com.enderio.enderio.api.conduits.connection.ConnectionStatus;
import com.enderio.enderio.api.conduits.network.node.ConduitNode;
import com.enderio.enderio.api.io.RedstoneControl;
import com.enderio.enderio.content.conduits.network.ConduitNetworkImpl;
import com.enderio.enderio.content.conduits.network.ConduitNodeImpl;
import com.enderio.enderio.content.conduits.type.item.ItemConduitConnectionConfig;
import com.enderio.enderio.content.conduits.type.item.ItemConduitNodeData;
import com.enderio.enderio.content.conduits.type.item.ItemConduitTicker;
import com.enderio.enderio.init.EIOConduits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class ItemNetworkRoutingTests {
    private static final Direction FACE = Direction.NORTH;
    private static final ItemConduitConnectionConfig EXTRACT = ItemConduitConnectionConfig.DEFAULT
        .withExtractRedstoneControl(RedstoneControl.ALWAYS_ACTIVE);
    private static final ItemConduitConnectionConfig INSERT = ItemConduitConnectionConfig.DEFAULT
        .withIsExtract(false).withIsInsert(true);
    private Holder<Conduit<?, ?>> item;
    private ServerLevel level;

    @BeforeEach
    void setup(MinecraftServer server) {
        item = server.registryAccess().lookupOrThrow(EnderIORegistries.Keys.CONDUIT).getOrThrow(EIOConduits.ITEM);
        level = mock(ServerLevel.class, withSettings().stubOnly());
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.shouldTickBlocksAt(any(BlockPos.class))).thenReturn(true);
        when(level.getGameTime()).thenReturn(0L);
    }

    private record Endpoint(ConduitNodeImpl node, FakeConduitNodeAttachment attachment, ItemStackHandler items) {
        int count() {
            return items.getStackInSlot(0).getCount();
        }
    }

    private Endpoint endpoint(int x, ItemConduitConnectionConfig config) {
        var node = new ConduitNodeImpl(item, new BlockPos(x, 64, 0));
        var attachment = new FakeConduitNodeAttachment(level, item);
        attachment.setConnectionStatus(item, FACE, ConnectionStatus.CONNECTED_BLOCK);
        attachment.setConnectionConfig(item, FACE, config);
        var inventory = new ItemStackHandler(1);
        attachment.setNeighborItems(FACE, inventory);
        node.attach(attachment);
        return new Endpoint(node, attachment, inventory);
    }

    private void tick(ConduitNetworkImpl network) {
        network.ensureCachesReady();
        ItemConduitTicker.INSTANCE.tick(level, network, 0);
    }

    @Test
    void gatedExtractionHonorsChannelPriorityAndRoundRobinAcrossReload(MinecraftServer server) {
        var source = endpoint(0, EXTRACT.withIsRoundRobin(true)
            .withExtractRedstoneControl(RedstoneControl.ACTIVE_WITH_SIGNAL));
        var near = endpoint(1, INSERT);
        var far = endpoint(2, INSERT.withPriority(5));
        var wrongColor = endpoint(3, INSERT.withInsertChannel(DyeColor.BLUE).withPriority(10));
        var network = source.node.getNetwork();
        network.connect(source.node, near.node);
        network.connect(near.node, far.node);
        network.connect(far.node, wrongColor.node);

        source.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
        tick(network);
        assertEquals(1, source.count());
        assertTrue(network.extractConnections(DyeColor.GREEN).isEmpty());

        source.attachment.setPoweredChannel(DyeColor.RED);
        source.node.onRedstoneChanged();
        tick(network);
        assertEquals(0, source.count());
        assertEquals(1, far.count());
        assertEquals(0, near.count());
        assertEquals(0, wrongColor.count());
        assertEquals(1, source.node.getNodeData(ItemConduitNodeData.TYPE).getIndex(FACE));

        var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var tag = ConduitNetworkImpl.CODEC.encodeStart(ops, network).getOrThrow();
        var restored = ConduitNetworkImpl.CODEC.parse(ops, tag).getOrThrow();
        var endpoints = List.of(source, near, far, wrongColor);
        for (var node : restored.nodes()) {
            var endpoint = endpoints.get(node.pos().getX());
            assertEquals(endpoint.node.pos(), node.pos());
            node.attach(endpoint.attachment);
        }

        source.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
        tick(restored);
        assertEquals(0, source.count());
        assertEquals(1, far.count());
        assertEquals(1, near.count());
        assertEquals(0, wrongColor.count());

        var restoredSource = restored.nodes().stream().filter(n -> n.pos().equals(source.node.pos())).findFirst().orElseThrow();
        source.attachment.setPoweredChannel(null);
        restoredSource.onRedstoneChanged();
        source.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
        tick(restored);
        assertEquals(1, source.count());
        assertEquals(2, near.count() + far.count() + wrongColor.count());
    }

    @Test
    void splittingAndReconnectingTheBridgeRefreshesDeliveryPaths() {
        var source = endpoint(0, EXTRACT);
        var bridge = endpoint(1, INSERT.withIsInsert(false));
        var target = endpoint(2, INSERT);
        var network = source.node.getNetwork();
        network.connect(source.node, bridge.node);
        network.connect(bridge.node, target.node);
        source.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
        tick(network);
        assertEquals(1, target.count());

        network.disconnect(bridge.node, target.node);
        assertNotSame(source.node.getNetwork(), target.node.getNetwork());
        source.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
        tick(source.node.getNetwork());
        assertEquals(1, source.count());
        assertEquals(1, target.count());
        assertTrue(source.node.getNetwork().insertConnectionsFrom(new ConduitBlockConnection(source.node, FACE)).isEmpty());

        source.node.getNetwork().connect(bridge.node, target.node);
        tick(source.node.getNetwork());
        assertSame(source.node.getNetwork(), target.node.getNetwork());
        assertEquals(0, source.count());
        assertEquals(2, target.count());
    }

    private static final class CountingNetwork extends ConduitNetworkImpl {
        int visited;

        CountingNetwork(ConduitNodeImpl first) {
            super(first.conduit().value().type(), first);
        }

        @Override
        public Set<? extends ConduitNode> neighbors(ConduitNode node) {
            visited++;
            return super.neighbors(node);
        }
    }

    @Test
    void measureEndpointChainCacheRebuild() {
        measureChain(6); // Warm up the graph and attachment code before the reported sizes.
        for (int size : List.of(12, 32)) {
            measureChain(size);
            measureChain(size);
        }
    }

    private void measureChain(int size) {
        var first = endpoint(0, INSERT);
        first.node.getNetwork().remove(first.node);
        var network = new CountingNetwork(first.node);
        var last = first;
        for (int x = 1; x < size; x++) {
            var next = endpoint(x, INSERT);
            network.connect(last.node, next.node);
            last = next;
        }
        long start = System.nanoTime();
        network.ensureCachesReady();
        long elapsed = System.nanoTime() - start;

        assertEquals(size, network.blockConnections().size());
        int pairs = 0;
        int lengths = 0;
        for (var from : network.blockConnections()) {
            var paths = network.blockConnectionsAccessibleFrom(from);
            assertEquals(size - 1, paths.size());
            for (var path : paths) {
                assertEquals(from, path.start());
                assertEquals(1 + from.node().pos().distManhattan(path.end().node().pos()), path.length());
                lengths += path.length();
            }
            pairs += paths.size();
        }
        assertEquals(size * (size - 1), pairs);
        assertEquals(size * (size * size - 1) / 3 + pairs, lengths);
        assertTrue(network.visited > 0);
        System.out.printf("chain rebuild: nodes=%d pairs=%d lengthSum=%d neighborQueries=%d elapsedNs=%d%n",
            size, pairs, lengths, network.visited, elapsed);
    }
}
