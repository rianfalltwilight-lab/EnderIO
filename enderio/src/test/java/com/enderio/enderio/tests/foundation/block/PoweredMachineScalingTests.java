package com.enderio.enderio.tests.foundation.block;

import com.enderio.enderio.api.capacitor.CapacitorData;
import com.enderio.enderio.api.capacitor.CapacitorModifier;
import com.enderio.enderio.api.capacitor.CapacitorScalable;
import com.enderio.enderio.api.capacitor.QuadraticScalable;
import com.enderio.enderio.api.io.energy.EnergyIOMode;
import com.enderio.enderio.foundation.block.entity.PoweredMachineBlockEntity;
import com.enderio.enderio.foundation.block.entity.flags.CapacitorSupport;
import com.enderio.enderio.foundation.inventory.MachineInventoryLayout;
import com.enderio.enderio.init.EIOBlockEntities;
import com.enderio.enderio.init.EIOBlocks;
import com.enderio.enderio.init.EIOItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class PoweredMachineScalingTests {
    @Test
    void bothConfigAndCapacitorChangesReachBuiltinsAndEagerAddonScalables() {
        var config = new AtomicInteger(10);
        var capacity = new QuadraticScalable(CapacitorModifier.ENERGY_CAPACITY, config::get);
        var addon = new CapacitorScalable() {
            public Supplier<Float> scaleF(Supplier<CapacitorData> capacitor) {
                throw new AssertionError();
            }
            public Supplier<Integer> scaleI(Supplier<CapacitorData> capacitor) {
                int snapshot = config.get() + (int) capacitor.get().base();
                return () -> snapshot;
            }
        };
        var machine = new PoweredMachineBlockEntity(EIOBlockEntities.DRAIN.get(), BlockPos.ZERO,
                EIOBlocks.DRAIN.get().defaultBlockState(), false, CapacitorSupport.REQUIRED, EnergyIOMode.Input,
                capacity, addon) {
            public boolean isActive() {
                return false;
            }
            public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
                return null;
            }
            protected MachineInventoryLayout createInventoryLayout() {
                return MachineInventoryLayout.builder().capacitor().build();
            }
        };
        machine.setLevel(mock(ServerLevel.class));
        machine.getInventory().setStackInSlot(machine.getCapacitorSlotIndex(), EIOItems.BASIC_CAPACITOR.toStack());
        assertEquals(10, machine.getMaxEnergyStored());
        assertEquals(11, machine.getMaxEnergyUse());
        config.set(25);
        assertEquals(25, machine.getMaxEnergyStored());
        assertEquals(26, machine.getMaxEnergyUse());
        machine.getInventory().setStackInSlot(machine.getCapacitorSlotIndex(), EIOItems.DOUBLE_LAYER_CAPACITOR.toStack());
        assertEquals(100, machine.getMaxEnergyStored());
        assertEquals(27, machine.getMaxEnergyUse());
    }
}
