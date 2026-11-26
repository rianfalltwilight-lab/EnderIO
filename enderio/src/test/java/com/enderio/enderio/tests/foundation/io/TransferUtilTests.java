package com.enderio.enderio.tests.foundation.io;

import com.enderio.enderio.foundation.io.TransferUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
}
