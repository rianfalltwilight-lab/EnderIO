package com.enderio.enderio.tests.conduits.network;

import java.util.List;
import net.minecraft.nbt.NbtOps;
import io.netty.buffer.Unpooled;
import com.enderio.enderio.content.conduits.ConduitLine;
import com.enderio.enderio.api.EnderIORegistries;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.connection.ConnectionStatus;
import com.enderio.enderio.api.io.RedstoneControl;
import com.enderio.enderio.content.conduits.network.ConduitNetworkImpl;
import com.enderio.enderio.content.conduits.network.ConduitNodeImpl;
import com.enderio.enderio.content.conduits.probe.ConduitDiagnostics;
import com.enderio.enderio.content.conduits.type.item.ItemConduitConnectionConfig;
import com.enderio.enderio.content.conduits.type.item.ItemConduitNodeData;
import com.enderio.enderio.content.conduits.type.item.ItemConduitTicker;
import com.enderio.enderio.content.conduits.type.item.ItemExtractState;
import com.enderio.enderio.content.conduits.type.item.ItemTransferStatus;
import com.enderio.enderio.init.EIOConduits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class ItemExtractionTests {
    private static final Direction FACE = Direction.NORTH;
    private static final ItemConduitConnectionConfig EXTRACT = ItemConduitConnectionConfig.DEFAULT
        .withExtractRedstoneControl(RedstoneControl.ALWAYS_ACTIVE);
    private static final ItemConduitConnectionConfig INSERT = ItemConduitConnectionConfig.DEFAULT
        .withIsExtract(false).withIsInsert(true);

    private Holder<Conduit<?, ?>> conduit;
    private ServerLevel level;
    private long gameTime;

    @BeforeEach
    void setup(MinecraftServer server) {
        // The ender item conduit: 512 items every 10 ticks.
        conduit = server.registryAccess().lookupOrThrow(EnderIORegistries.Keys.CONDUIT).getOrThrow(EIOConduits.VIBRANT_ITEM);
        gameTime = 0;
        level = mock(ServerLevel.class, withSettings().stubOnly());
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.shouldTickBlocksAt(any(BlockPos.class))).thenReturn(true);
        when(level.getGameTime()).thenAnswer(call -> gameTime);
    }

    /** A drawer-like inventory: one slot holding far more than a stack. */
    private static final class BulkSlot implements IItemHandler {
        private final ItemStack type;
        private int count;
        private int simulations;

        private BulkSlot(ItemStack type, int count) {
            this.type = type;
            this.count = count;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return count == 0 ? ItemStack.EMPTY : type.copyWithCount(count);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            int taken = Math.min(amount, count);
            if (simulate) {
                simulations++;
            } else {
                count -= taken;
            }
            return taken == 0 ? ItemStack.EMPTY : type.copyWithCount(taken);
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }

    private record Endpoint(ConduitNodeImpl node, FakeConduitNodeAttachment attachment) {
    }

    private Endpoint endpoint(int x, ItemConduitConnectionConfig config, IItemHandler items) {
        var node = new ConduitNodeImpl(conduit, new BlockPos(x, 64, 0));
        var attachment = new FakeConduitNodeAttachment(level, conduit);
        attachment.setConnectionStatus(conduit, FACE, ConnectionStatus.CONNECTED_BLOCK);
        attachment.setConnectionConfig(conduit, FACE, config);
        attachment.setNeighborItems(FACE, items);
        node.attach(attachment);
        return new Endpoint(node, attachment);
    }

    private static ConduitNetworkImpl line(Endpoint... endpoints) {
        var network = endpoints[0].node().getNetwork();
        for (int i = 1; i < endpoints.length; i++) {
            network.connect(endpoints[i - 1].node(), endpoints[i].node());
        }
        return network;
    }

    private void tickAt(ConduitNetworkImpl network, long time) {
        gameTime = time;
        network.ensureCachesReady();
        ItemConduitTicker.INSTANCE.tick(level, network, 0);
    }

    private static ItemExtractState state(Endpoint source) {
        return source.node().getNodeData(ItemConduitNodeData.TYPE).extractState(FACE);
    }

    private static int total(IItemHandler handler) {
        int total = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            total += handler.getStackInSlot(i).getCount();
        }
        return total;
    }

    @Test
    void aSingleBulkSlotGivesUpToTheWholeRateEachCycle() {
        var drawer = new BulkSlot(new ItemStack(Items.IRON_INGOT), 10_000);
        var chest = new ItemStackHandler(27);
        var source = endpoint(0, EXTRACT, drawer);
        var network = line(source, endpoint(1, INSERT, chest));

        tickAt(network, 0);

        assertEquals(512, total(chest));
        assertEquals(10_000 - 512, drawer.count);
        assertEquals(ItemTransferStatus.MOVED, state(source).status());
        assertEquals(512, state(source).lastMoved());
    }

    @Test
    void unstackableItemsAreCappedByTheBatchLimit() {
        var drawer = new BulkSlot(new ItemStack(Items.IRON_SWORD), 1000);
        var chest = new ItemStackHandler(300);
        var network = line(endpoint(0, EXTRACT, drawer), endpoint(1, INSERT, chest));

        tickAt(network, 0);

        assertEquals(128, total(chest));
        assertEquals(1000 - 128, drawer.count);
    }

    @Test
    void whatTheFirstDestinationCannotTakeGoesToTheNext() {
        var drawer = new BulkSlot(new ItemStack(Items.IRON_INGOT), 1000);
        var small = new ItemStackHandler(1);
        small.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 54));
        var large = new ItemStackHandler(27);
        var network = line(endpoint(0, EXTRACT, drawer), endpoint(1, INSERT.withPriority(5), small), endpoint(2, INSERT, large));

        tickAt(network, 0);

        assertEquals(64, total(small));
        assertEquals(502, total(large));
        assertEquals(1000 - 512, drawer.count);
    }

    @Test
    void idleExtractionBacksOffAndAConfigChangeWakesIt() {
        var drawer = new BulkSlot(new ItemStack(Items.IRON_INGOT), 0);
        var chest = new ItemStackHandler(27);
        var source = endpoint(0, EXTRACT, drawer);
        var network = line(source, endpoint(1, INSERT, chest));

        // Checks at 0, 10 and 20, then the wait doubles: 20, 40 and at most 80 ticks.
        for (long time = 0; time <= 400; time += 10) {
            tickAt(network, time);
        }
        assertEquals(ItemTransferStatus.SOURCE_EMPTY, state(source).status());
        assertEquals(9, drawer.simulations, "41 cycles, but only checked at 0, 10, 20, 40, 80, 160, 240, 320 and 400");
        assertTrue(state(source).ticksUntilAwake(gameTime) > 0);

        drawer.count = 100;
        tickAt(network, 410);
        assertEquals(0, total(chest), "still asleep");

        source.node().onConfigChanged();
        tickAt(network, 420);
        assertEquals(100, total(chest));
        assertEquals(0, state(source).ticksUntilAwake(gameTime));
    }

    @Test
    void reportsWhyNothingMoved(MinecraftServer server) {
        var drawer = new BulkSlot(new ItemStack(Items.IRON_INGOT), 100);
        var full = new ItemStackHandler(1);
        full.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 64));
        var source = endpoint(0, EXTRACT, drawer);
        var network = line(source, endpoint(1, INSERT, full));

        tickAt(network, 0);

        assertEquals(ItemTransferStatus.DESTINATIONS_FULL, state(source).status());
        assertEquals(100, drawer.count);
        var report = ConduitDiagnostics.describe(conduit, source.node(), FACE, gameTime);
        assertEquals(4, report.size(), report.toString());
        assertTrue(report.get(3).toString().contains("destinations_full"), report.toString());
    }

    @Test
    void aLongerIntervalMovesMoreAtOnceButNoFaster() {
        var drawer = new BulkSlot(new ItemStack(Items.IRON_INGOT), 10_000);
        var chest = new ItemStackHandler(100);
        var source = endpoint(0, EXTRACT.withExtractInterval(35), drawer);
        var network = line(source, endpoint(1, INSERT, chest));

        // 35 ticks round up to four 10 tick cycles.
        tickAt(network, 0);
        assertEquals(2048, total(chest));
        for (long time = 10; time < 40; time += 10) {
            tickAt(network, time);
        }
        assertEquals(2048, total(chest));

        // A network change does not let it run early.
        source.node().onRedstoneChanged();
        tickAt(network, 30);
        assertEquals(2048, total(chest));

        tickAt(network, 40);
        assertEquals(4096, total(chest));
    }

    @Test
    void balancedDeliverySharesEachCycleBetweenDestinations() {
        var drawer = new BulkSlot(new ItemStack(Items.IRON_INGOT), 10_000);
        var first = new ItemStackHandler(27);
        var second = new ItemStackHandler(27);
        var network = line(endpoint(0, EXTRACT.withIsBalanced(true), drawer), endpoint(1, INSERT.withPriority(5), first),
            endpoint(2, INSERT, second));

        tickAt(network, 0);

        assertEquals(256, total(first));
        assertEquals(256, total(second));
    }

    @Test
    void newSettingsSurviveSavingAndSyncing(MinecraftServer server) {
        var config = EXTRACT.withExtractInterval(100).withIsBalanced(true).withPriority(-7);
        var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var codec = ItemConduitConnectionConfig.CODEC.codec();
        assertEquals(config, codec.parse(ops, codec.encodeStart(ops, config).getOrThrow()).getOrThrow());

        var buffer = Unpooled.buffer();
        ItemConduitConnectionConfig.STREAM_CODEC.encode(buffer, config);
        assertEquals(config, ItemConduitConnectionConfig.STREAM_CODEC.decode(buffer));
        assertEquals(1200, config.withExtractInterval(99_999).extractInterval());
    }

    @Test
    void linesRunAlongTheLongestAxisFirst() {
        var route = ConduitLine.route(new BlockPos(0, 0, 0), new BlockPos(1, 0, -3));

        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(0, 0, -1), new BlockPos(0, 0, -2), new BlockPos(0, 0, -3),
            new BlockPos(1, 0, -3)), route);
        assertEquals(List.of(BlockPos.ZERO), ConduitLine.route(BlockPos.ZERO, BlockPos.ZERO));
    }
}
