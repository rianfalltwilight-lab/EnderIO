package com.enderio.enderio.content.machines.vacuum.chest;

import com.enderio.enderio.api.EnderIOCapabilities;
import com.enderio.enderio.api.filter.ItemFilterContext;
import com.enderio.enderio.api.filter.ItemFilterDirection;
import com.enderio.enderio.config.machines.MachinesConfig;
import com.enderio.enderio.content.filters.item.NonMutatingItemFilter;
import com.enderio.enderio.content.machines.vacuum.VacuumMachineBlockEntity;
import com.enderio.enderio.foundation.inventory.MachineInventoryLayout;
import com.enderio.enderio.foundation.inventory.MachineInventoryLayout.Builder;
import com.enderio.enderio.init.EIOBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

public class VacuumChestBlockEntity extends VacuumMachineBlockEntity<ItemEntity> {

    public VacuumChestBlockEntity(BlockPos worldPosition, BlockState blockState) {
        super(EIOBlockEntities.VACUUM_CHEST.get(), worldPosition, blockState, ItemEntity.class);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new VacuumChestMenu(containerId, inventory, this);
    }

    @Override
    public MachineInventoryLayout createInventoryLayout() {
        return extractableGUISlot(MachineInventoryLayout.builder(), 27)
                .slot(slot -> slot.guiInsert()
                        .guiExtract()
                        .filter((i, s) -> s.getCapability(EnderIOCapabilities.ITEM_FILTER) != null))
                .slotAccess(FILTER)
                .build();
    }

    @Override
    public void handleEntity(ItemEntity entity) {
        if (entity.level().isClientSide()) {
            return;
        }
        var inventory = getInventory();
        ItemStack original = entity.getItem();
        ItemStack offered = original.copy();
        var filter = FILTER.getItemStack(inventory).getCapability(EnderIOCapabilities.ITEM_FILTER);
        if (filter != null) {
            offered = filter.test(offered, new ItemFilterContext(inventory, ItemFilterDirection.INSERT));
            if (offered.getCount() > original.getCount()) {
                offered = offered.copyWithCount(original.getCount());
            }
        }
        int allowed = offered.getCount();
        for (int i = 0; i < inventory.getSlots() && !offered.isEmpty(); i++) {
            offered = inventory.insertItem(i, offered, false);
        }
        int inserted = allowed - offered.getCount();
        if (inserted == original.getCount()) {
            entity.discard();
        } else if (inserted > 0) {
            entity.setItem(original.copyWithCount(original.getCount() - inserted));
        }
    }

    @Override
    public String getColor() {
        return MachinesConfig.CLIENT.BLOCKS.VACUUM_CHEST_RANGE_COLOR.get();
    }

    @Override
    public Predicate<ItemEntity> getFilter() {
        var filter = FILTER.getItemStack(this).getCapability(EnderIOCapabilities.ITEM_FILTER);
        if (filter != null) {
            var context = new ItemFilterContext(getInventory(), ItemFilterDirection.INSERT);
            return itemEntity -> {
                ItemStack stack = itemEntity.getItem();
                return !filter.test(filter instanceof NonMutatingItemFilter ? stack : stack.copy(), context).isEmpty();
            };
        }

        return super.getFilter();
    }

    // Slot config

    public Builder extractableGUISlot(Builder builder, int count) {
        for (int i = 0; i < count; i++) {
            builder.slot(slot -> slot.guiInsert().guiExtract().extract());
        }
        return builder;
    }
}
