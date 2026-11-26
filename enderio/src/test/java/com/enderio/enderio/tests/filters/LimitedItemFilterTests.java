package com.enderio.enderio.tests.filters;

import com.enderio.enderio.api.filter.ItemFilterContext;
import com.enderio.enderio.api.filter.ItemFilterDirection;
import net.neoforged.neoforge.items.ItemStackHandler;
import com.enderio.enderio.content.filters.item.general.DamageFilterMode;
import com.enderio.enderio.content.filters.item.limited.LimitedItemFilter;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LimitedItemFilterTests {
    @Test
    public void testEqualsSameValues() {
        var filter1 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), false, DamageFilterMode.IGNORE);
        var filter2 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), false, DamageFilterMode.IGNORE);

        Assertions.assertEquals(filter1, filter2);
        Assertions.assertEquals(filter1.hashCode(), filter2.hashCode());
    }

    @Test
    public void testEqualsSameInstance() {
        var filter1 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), false, DamageFilterMode.IGNORE);

        Assertions.assertEquals(filter1, filter1);
        Assertions.assertEquals(filter1.hashCode(), filter1.hashCode());
    }

    @Test
    public void testNotEqualsDifferentMatches() {
        var filter1 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), false, DamageFilterMode.IGNORE);
        var filter2 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.COBBLESTONE, 1)), false, DamageFilterMode.IGNORE);

        Assertions.assertNotEquals(filter1, filter2);
    }

    @Test
    public void testNotEqualsDifferentShouldCompareComponents() {
        var filter1 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), false, DamageFilterMode.IGNORE);
        var filter2 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), true, DamageFilterMode.IGNORE);

        Assertions.assertNotEquals(filter1, filter2);
    }

    @Test
    public void testNotEqualsDifferentDamageFilterMode() {
        var filter1 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), false, DamageFilterMode.IGNORE);
        var filter2 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), false, DamageFilterMode.IS_DAMAGEABLE);

        Assertions.assertNotEquals(filter1, filter2);
    }

    @Test
    public void testNotEqualsDifferentMatchCount() {
        var filter1 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1)), false, DamageFilterMode.IGNORE);
        var filter2 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 1), new ItemStack(Items.DIRT, 1)), false, DamageFilterMode.IGNORE);

        Assertions.assertNotEquals(filter1, filter2);
    }

    @Test
    public void testEmptyListEquals() {
        var filter1 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY), false, DamageFilterMode.IGNORE);
        var filter2 = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY), false, DamageFilterMode.IGNORE);

        Assertions.assertEquals(filter1, filter2);
        Assertions.assertEquals(filter1.hashCode(), filter2.hashCode());
    }

    @Test
    void liveLimitFollowsEveryInsertionAndExtractionOnTheSameHandler() {
        var filter = new LimitedItemFilter(NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.SAND, 10)), false, DamageFilterMode.IGNORE);
        var target = new ItemStackHandler(2);
        var insert = new ItemFilterContext(target, ItemFilterDirection.INSERT);
        var extract = new ItemFilterContext(target, ItemFilterDirection.EXTRACT);
        var offered = new ItemStack(Items.SAND, 6);
        target.setStackInSlot(0, new ItemStack(Items.SAND, 7));
        Assertions.assertEquals(3, filter.test(offered, insert).getCount());
        target.setStackInSlot(1, new ItemStack(Items.SAND, 5));
        Assertions.assertTrue(filter.test(offered, insert).isEmpty());
        Assertions.assertEquals(2, filter.test(offered, extract).getCount());
        target.extractItem(1, 4, false);
        Assertions.assertEquals(2, filter.test(offered, insert).getCount());
        Assertions.assertTrue(filter.test(offered, extract).isEmpty());
        Assertions.assertEquals(6, offered.getCount());
    }
}
