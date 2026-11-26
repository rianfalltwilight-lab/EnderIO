package com.enderio.enderio.tests.foundation.io;

import com.enderio.enderio.foundation.io.TransferUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

public class TransferUtilTests {

    // region Items

    @Test
    public void distributeItems_Push() {
        // Arrange.
        ItemStackHandler from = new ItemStackHandler(1);
        ItemStackHandler to = new ItemStackHandler(1);

        from.setStackInSlot(0, new ItemStack(Items.GRASS_BLOCK, 3));

        // Act.
        TransferUtil.distributeItems(true, false, from, to);

        // Assert.
        Assertions.assertTrue(from.getStackInSlot(0).isEmpty());
        Assertions.assertEquals(Items.GRASS_BLOCK, to.getStackInSlot(0).getItem());
        Assertions.assertEquals(3, to.getStackInSlot(0).getCount());
    }

    @Test
    public void distributeItems_PushMultipleSlotsOverflows() {
        // Arrange.
        ItemStackHandler from = new ItemStackHandler(1);
        ItemStackHandler to = new ItemStackHandler(2);

        from.setStackInSlot(0, new ItemStack(Items.GRASS_BLOCK, 3));
        to.setStackInSlot(0, new ItemStack(Items.GRASS_BLOCK, 63));

        // Act.
        TransferUtil.distributeItems(true, false, from, to);

        // Assert.
        Assertions.assertTrue(from.getStackInSlot(0).isEmpty());
        Assertions.assertEquals(Items.GRASS_BLOCK, to.getStackInSlot(0).getItem());
        Assertions.assertEquals(64, to.getStackInSlot(0).getCount());
        Assertions.assertEquals(Items.GRASS_BLOCK, to.getStackInSlot(1).getItem());
        Assertions.assertEquals(2, to.getStackInSlot(1).getCount());
    }

    @Test
    public void distributeItems_Pull() {
        // Arrange.
        ItemStackHandler from = new ItemStackHandler(1);
        ItemStackHandler to = new ItemStackHandler(1);

        to.setStackInSlot(0, new ItemStack(Items.GRASS_BLOCK, 3));

        // Act.
        TransferUtil.distributeItems(false, true, from, to);

        // Assert.
        Assertions.assertTrue(to.getStackInSlot(0).isEmpty());
        Assertions.assertEquals(Items.GRASS_BLOCK, from.getStackInSlot(0).getItem());
        Assertions.assertEquals(3, from.getStackInSlot(0).getCount());
    }

    @Test
    public void distributeItems_PullMultipleSlotsOverflows() {
        // Arrange.
        ItemStackHandler from = new ItemStackHandler(2);
        ItemStackHandler to = new ItemStackHandler(1);

        from.setStackInSlot(0, new ItemStack(Items.GRASS_BLOCK, 63));
        to.setStackInSlot(0, new ItemStack(Items.GRASS_BLOCK, 3));

        // Act.
        TransferUtil.distributeItems(false, true, from, to);

        // Assert.
        Assertions.assertTrue(to.getStackInSlot(0).isEmpty());
        Assertions.assertEquals(Items.GRASS_BLOCK, from.getStackInSlot(0).getItem());
        Assertions.assertEquals(64, from.getStackInSlot(0).getCount());
        Assertions.assertEquals(Items.GRASS_BLOCK, from.getStackInSlot(1).getItem());
        Assertions.assertEquals(2, from.getStackInSlot(1).getCount());
    }

    @Test
    void blockedDestinationsSimulateOncePerNonemptySource() {
        var source = new ItemStackHandler(2) {
            int simulations;

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (simulate) {
                    simulations++;
                }
                return super.extractItem(slot, amount, simulate);
            }
        };
        source.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 5));
        var target = new ItemStackHandler(3);
        for (int slot = 0; slot < target.getSlots(); slot++) {
            target.setStackInSlot(slot, new ItemStack(Items.DIRT, 64));
        }

        TransferUtil.distributeItems(true, false, source, target);

        Assertions.assertEquals(1, source.simulations);
        Assertions.assertEquals(5, source.getStackInSlot(1).getCount());
    }

    @Test
    void partialInsertionResimulatesAnExtractionLimitedSource() {
        var source = new ItemStackHandler(1) {
            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return super.extractItem(slot, Math.min(amount, 2), simulate);
            }
        };
        source.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 5));
        var target = new ItemStackHandler(2);
        target.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 63));

        TransferUtil.distributeItems(true, false, source, target);

        Assertions.assertEquals(64, target.getStackInSlot(0).getCount());
        Assertions.assertEquals(2, target.getStackInSlot(1).getCount());
        Assertions.assertEquals(2, source.getStackInSlot(0).getCount());
    }

    // endregion

    @Test
    void emptyExposedEnergySkipsAllSideQueries() {
        TransferUtil.distributeEnergyEvenly(new EnergyStorage(100), dir -> Assertions.fail("Checked a side"),
            dir -> Assertions.fail("Looked up a source"), dir -> Assertions.fail("Looked up a neighbor"));
    }

    @Test
    void energyUsesFirstEligibleBudgetAndChargesEachSidedHandlerInOrder() {
        var shared = new EnergyStorage(10);
        shared.receiveEnergy(10, false);
        var selfNeighbor = mock(IEnergyStorage.class, delegatesTo(shared));
        var disconnected = mock(IEnergyStorage.class, delegatesTo(shared));
        var first = mock(IEnergyStorage.class, delegatesTo(shared));
        var second = mock(IEnergyStorage.class, delegatesTo(shared));
        var third = mock(IEnergyStorage.class, delegatesTo(shared));
        var limited = new EnergyStorage(1);
        var twoFaces = new EnergyStorage(10);
        var sources = Map.of(Direction.DOWN, selfNeighbor, Direction.UP, disconnected, Direction.NORTH, first,
            Direction.SOUTH, second, Direction.WEST, third);
        Map<Direction, IEnergyStorage> neighbors = Map.of(Direction.DOWN, selfNeighbor, Direction.NORTH, limited,
            Direction.SOUTH, twoFaces, Direction.WEST, twoFaces);

        TransferUtil.distributeEnergyEvenly(shared, dir -> dir != Direction.EAST, sources::get, neighbors::get);

        verify(selfNeighbor, never()).extractEnergy(anyInt(), anyBoolean());
        verify(disconnected, never()).extractEnergy(anyInt(), anyBoolean());
        var order = inOrder(first, second, third);
        order.verify(first).extractEnergy(Integer.MAX_VALUE, true);
        order.verify(first).extractEnergy(1, false);
        order.verify(second).extractEnergy(4, false);
        order.verify(third).extractEnergy(5, false);
        Assertions.assertEquals(1, limited.getEnergyStored());
        Assertions.assertEquals(9, twoFaces.getEnergyStored());
        Assertions.assertEquals(0, shared.getEnergyStored());
    }

    @Test
    void nestedEnergyTransferKeepsTheOuterRecipientSnapshot() {
        var outerSource = new EnergyStorage(10);
        outerSource.receiveEnergy(10, false);
        var innerSource = new EnergyStorage(3);
        innerSource.receiveEnergy(3, false);
        var innerTarget = new EnergyStorage(3);
        var originalSecond = new EnergyStorage(10);
        var replacement = new EnergyStorage(10);
        var targets = new EnumMap<Direction, IEnergyStorage>(Direction.class);
        var first = new EnergyStorage(10) {
            @Override
            public int receiveEnergy(int amount, boolean simulate) {
                targets.put(Direction.SOUTH, replacement);
                TransferUtil.distributeEnergyEvenly(innerSource, dir -> dir == Direction.UP, dir -> innerSource, dir -> innerTarget);
                return super.receiveEnergy(amount, simulate);
            }
        };
        targets.put(Direction.NORTH, first);
        targets.put(Direction.SOUTH, originalSecond);

        TransferUtil.distributeEnergyEvenly(outerSource, targets::containsKey, dir -> outerSource, targets::get);

        Assertions.assertEquals(0, innerSource.getEnergyStored());
        Assertions.assertEquals(3, innerTarget.getEnergyStored());
        Assertions.assertEquals(0, outerSource.getEnergyStored());
        Assertions.assertEquals(5, first.getEnergyStored());
        Assertions.assertEquals(5, originalSecond.getEnergyStored());
        Assertions.assertEquals(0, replacement.getEnergyStored());
    }

    @Test
    void worldLookupUsesTheOppositeFaceOnTheNeighbor() {
        var level = mock(Level.class);
        var position = new BlockPos(1, 2, 3);
        var source = new EnergyStorage(7);
        var target = new EnergyStorage(7);
        source.receiveEnergy(7, false);
        when(level.getCapability(Capabilities.EnergyStorage.BLOCK, position, Direction.EAST)).thenReturn(source);
        when(level.getCapability(Capabilities.EnergyStorage.BLOCK, position.east(), Direction.WEST)).thenReturn(target);

        TransferUtil.distributeEnergyEvenly(level, position, dir -> dir == Direction.EAST);

        Assertions.assertEquals(0, source.getEnergyStored());
        Assertions.assertEquals(7, target.getEnergyStored());
    }
}
