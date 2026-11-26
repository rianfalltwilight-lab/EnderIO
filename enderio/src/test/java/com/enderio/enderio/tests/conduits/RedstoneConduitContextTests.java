package com.enderio.enderio.tests.conduits;

import com.enderio.enderio.api.conduits.network.ConduitNetwork;
import com.enderio.enderio.content.conduits.type.redstone.RedstoneConduitNetworkContext;
import com.enderio.enderio.content.filters.redstone.RedstoneCountFilter;
import com.enderio.enderio.content.filters.redstone.RedstoneTLatchFilter;
import com.enderio.enderio.content.filters.redstone.RedstoneTimerFilter;
import com.enderio.enderio.init.EIODataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RedstoneConduitContextTests {
    @Test
    void zeroNeverActivatesAChannelAndPreviousSignalsAdvanceExactlyOnce() {
        var context = new RedstoneConduitNetworkContext();
        context.setSignal(DyeColor.RED, 0);
        context.setSignal(DyeColor.BLUE, -1);
        assertFalse(context.isActive());
        assertFalse(context.isActive(DyeColor.RED));
        assertFalse(context.isActive(DyeColor.BLUE));

        context.setSignal(DyeColor.RED, 10);
        context.setSignal(DyeColor.RED, 6);
        context.setSignal(DyeColor.RED, 0);
        assertEquals(10, context.getSignal(DyeColor.RED));
        assertTrue(context.isActive());
        assertTrue(context.isActive(DyeColor.RED));

        context.nextTick();
        assertTrue(context.isNew());
        assertFalse(context.isActive());
        assertEquals(10, context.getSignalLastTick(DyeColor.RED));
        context.nextTick();
        assertFalse(context.isNew());
        assertEquals(0, context.getSignalLastTick(DyeColor.RED));
    }

    @Test
    void mergeAndSplitCopyTheCurrentSignalsAndRestartTheNewNetworkDelay() {
        var context = new RedstoneConduitNetworkContext();
        context.nextTick();
        context.nextTick();
        context.setSignal(DyeColor.GREEN, 12);
        var other = new RedstoneConduitNetworkContext();
        other.setSignal(DyeColor.BLUE, 15);
        var network = mock(ConduitNetwork.class);
        for (var copy : List.of(context.mergeWith(other), context.split(network, Set.of(network)))) {
            assertTrue(copy.isNew());
            assertTrue(copy.isActive());
            assertEquals(12, copy.getSignal(DyeColor.GREEN));
            assertFalse(copy.isActive(DyeColor.BLUE));
            assertEquals(0, copy.getSignalLastTick(DyeColor.GREEN));
            copy.nextTick();
            assertTrue(copy.isNew());
            assertFalse(copy.isActive());
            assertEquals(12, context.getSignal(DyeColor.GREEN));
            copy.nextTick();
            assertFalse(copy.isNew());
        }
    }

    @Test
    void countAndLatchChangeOnEdgesAndTimerAdvancesByTwo() {
        var stack = new ItemStack(Items.STICK);
        stack.set(EIODataComponents.REDSTONE_COUNT_FILTER, new RedstoneCountFilter.Component(DyeColor.RED, 2, 0, true));
        stack.set(EIODataComponents.REDSTONE_TLATCH_FILTER, RedstoneTLatchFilter.INSTANCE);
        stack.set(EIODataComponents.REDSTONE_TIMER_FILTER, new RedstoneTimerFilter.Component(0, 4));
        var count = new RedstoneCountFilter(stack);
        var latch = new RedstoneTLatchFilter(stack);
        var timer = new RedstoneTimerFilter(stack);
        var ctx = new RedstoneConduitNetworkContext();

        ctx.setSignal(DyeColor.RED, 15);
        assertEquals(0, count.getOutputSignal(ctx, DyeColor.RED));
        assertEquals(1, count.getCount());
        assertEquals(15, latch.getOutputSignal(ctx, DyeColor.RED));
        assertEquals(0, count.getOutputSignal(ctx, DyeColor.RED));
        assertEquals(15, latch.getOutputSignal(ctx, DyeColor.RED));
        ctx.nextTick();
        assertEquals(0, count.getOutputSignal(ctx, DyeColor.RED));
        assertEquals(15, latch.getOutputSignal(ctx, DyeColor.RED));
        ctx.setSignal(DyeColor.RED, 15);
        assertEquals(15, count.getOutputSignal(ctx, DyeColor.RED));
        assertEquals(0, latch.getOutputSignal(ctx, DyeColor.RED));

        var level = mock(Level.class);
        assertEquals(0, timer.getInputSignal(level, BlockPos.ZERO, Direction.UP));
        assertEquals(2, timer.getTicks());
        assertEquals(15, timer.getInputSignal(level, BlockPos.ZERO, Direction.UP));
        assertEquals(0, timer.getTicks());
    }
}
