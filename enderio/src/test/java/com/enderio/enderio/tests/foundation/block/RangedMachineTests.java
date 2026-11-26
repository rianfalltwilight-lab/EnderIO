package com.enderio.enderio.tests.foundation.block;

import com.enderio.core.common.blockentity.EnderBlockEntity;
import com.enderio.core.common.recipes.OutputStack;
import com.enderio.enderio.api.filter.SoulFilter;
import com.enderio.enderio.content.filters.item.general.DamageFilterMode;
import com.enderio.enderio.content.filters.item.limited.LimitedItemFilter;
import com.enderio.enderio.content.machines.niard.NiardBlockEntity;
import com.enderio.enderio.content.machines.niard.NiardRangeIterator;
import com.enderio.enderio.content.machines.obelisks.attractor.AttractorObeliskBlockEntity;
import com.enderio.enderio.content.machines.vacuum.chest.VacuumChestBlockEntity;
import com.enderio.enderio.content.machines.vat.FermentingRecipe;
import com.enderio.enderio.content.machines.vat.VatBlockEntity;
import com.enderio.enderio.content.machines.vat.VatMenu;
import com.enderio.enderio.content.machines.wireless_charger.WirelessChargerBlockEntity;
import com.enderio.enderio.foundation.attachment.ActionRange;
import com.enderio.enderio.init.EIOBlocks;
import com.enderio.enderio.init.EIODataComponents;
import com.enderio.enderio.init.EIOItems;
import com.enderio.enderio.init.EIORecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class RangedMachineTests {
    @Test
    void attractorFindsNewGhastsAndAppliesPullOnConsecutiveTicks() {
        var pos = new BlockPos(8, 80, 8);
        var bounds = new AABB(pos).inflate(4);
        var filter = mock(SoulFilter.class);
        var machine = new AttractorObeliskBlockEntity(pos, EIOBlocks.ATTRACTOR_OBELISK.get().defaultBlockState()) {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            protected SoulFilter getSoulFilter() {
                return filter;
            }

            @Override
            public AABB getAABB() {
                return bounds;
            }
        };
        var level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(1L);
        machine.setLevel(level);
        var ghast = mock(Ghast.class);
        doReturn(EntityType.GHAST).when(ghast).getType();
        when(ghast.getBoundingBox()).thenReturn(new AABB(pos.above()));
        when(level.noCollision(same(ghast), any(AABB.class))).thenReturn(true);
        doReturn(List.of(), List.of(ghast), List.of(ghast)).when(level)
            .getEntities(ArgumentMatchers.<EntityTypeTest<Entity, Mob>>any(), eq(bounds), any());
        machine.serverTick();
        verify(ghast, never()).setDeltaMovement(any(Vec3.class));
        machine.serverTick();
        machine.serverTick();
        verify(ghast, times(2)).setDeltaMovement(any(Vec3.class));
    }

    @Test
    void niardScansItsWholeDefaultRangeAndResumesAnExtendedPass() {
        var level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(1L);
        var due = new AtomicBoolean(true);
        var pos = new BlockPos(8, 80, 8);
        var niard = new NiardBlockEntity(pos, EIOBlocks.NIARD.get().defaultBlockState()) {
            @Override
            public boolean canAct(int interval) {
                return interval == 40 && due.get();
            }
        };
        niard.setLevel(level);
        niard.getInventory().setStackInSlot(niard.getCapacitorSlotIndex(), EIOItems.BASIC_CAPACITOR.toStack());
        niard.getEnergyStorage().setEnergyStored(1);
        when(level.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
        niard.serverTick();
        verify(level, never()).getBlockState(any());

        niard.getFluidTank().setFluid(new FluidStack(Fluids.WATER, 1000));
        var range = new NiardRangeIterator(pos, niard.getActionRange());
        while (range.getIndex() < range.size() - 1) {
            range.moveToNextPosition();
        }
        when(level.getBlockState(range.current())).thenReturn(Blocks.AIR.defaultBlockState());
        niard.serverTick();
        verify(level).setBlock(range.current(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        assertEquals(0, niard.getEnergyStorage().getEnergyStored());

        niard.setActionRange(new ActionRange(7, false));
        niard.getEnergyStorage().setEnergyStored(1);
        niard.getFluidTank().setFluid(new FluidStack(Fluids.WATER, 1000));
        when(level.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
        clearInvocations(level);
        niard.serverTick();
        verify(level, times(121)).getBlockState(any());
        clearInvocations(level);
        due.set(false);
        niard.serverTick();
        verify(level, times(104)).getBlockState(any());
        assertEquals(1000, niard.getFluidTank().getFluidAmount());
    }

    @Test
    void chargerUsesBoundingBoxesAndKeepsSectionOrderWhenPowerIsScarce() {
        var level = mock(ServerLevel.class);
        var pos = new BlockPos(8, 80, 8);
        var charger = new WirelessChargerBlockEntity(pos, EIOBlocks.WIRELESS_CHARGER.get().defaultBlockState());
        when(level.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        charger.setLevel(level);
        charger.onLoad();
        charger.getInventory().setStackInSlot(charger.getCapacitorSlotIndex(), EIOItems.BASIC_CAPACITOR.toStack());
        charger.setActionRange(new ActionRange(2, false));
        var bounds = new AABB(pos).inflate(2);
        var edge = mock(ServerPlayer.class);
        var other = mock(ServerPlayer.class);
        var spectator = mock(ServerPlayer.class);
        when(spectator.isSpectator()).thenReturn(true);
        when(edge.getBoundingBox()).thenReturn(new AABB(10.9, 80, 8, 11.5, 82, 8.6));
        when(other.getBoundingBox()).thenReturn(new AABB(pos));
        var edgeStorage = heldBattery(edge);
        var otherStorage = heldBattery(other);
        doReturn(List.of(spectator, edge)).when(level).players();
        charger.getEnergyStorage().setEnergyStored(4);
        charger.chargeItem();
        assertEquals(4, edgeStorage.getEnergyStored());
        verify(level, never()).getEntitiesOfClass(eq(Player.class), any());

        doReturn(List.of(edge, other)).when(level).players();
        when(level.getEntitiesOfClass(Player.class, bounds)).thenReturn(List.of(other, edge));
        charger.getEnergyStorage().setEnergyStored(3);
        charger.chargeItem();
        assertEquals(3, otherStorage.getEnergyStored());
        assertEquals(4, edgeStorage.getEnergyStored());
    }

    private static EnergyStorage heldBattery(Player player) {
        var storage = new EnergyStorage(100);
        var stack = mock(ItemStack.class);
        when(stack.getCapability(Capabilities.EnergyStorage.ITEM)).thenReturn(storage);
        var inventory = new Inventory(player);
        inventory.setItem(0, stack);
        when(player.getInventory()).thenReturn(inventory);
        return storage;
    }

    @Test
    void limitedVacuumPickupLeavesUnacceptedItemsOnTheGround() {
        var chest = new VacuumChestBlockEntity(BlockPos.ZERO, EIOBlocks.VACUUM_CHEST.get().defaultBlockState());
        var filter = EIOItems.LIMITED_ITEM_FILTER.toStack();
        filter.set(EIODataComponents.LIMITED_ITEM_FILTER,
            new LimitedItemFilter(List.of(new ItemStack(Items.DIAMOND, 65)), false, DamageFilterMode.IGNORE));
        VacuumChestBlockEntity.FILTER.setStackInSlot(chest, filter);
        chest.getInventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 63));
        var stack = new AtomicReference<>(new ItemStack(Items.DIAMOND, 10));
        var drop = mock(ItemEntity.class);
        when(drop.level()).thenReturn(mock(ServerLevel.class));
        when(drop.getItem()).thenAnswer(invocation -> stack.get());
        doAnswer(invocation -> {
            stack.set(invocation.getArgument(0));
            return null;
        }).when(drop).setItem(any());

        chest.handleEntity(drop);
        assertEquals(64, chest.getInventory().getStackInSlot(0).getCount());
        assertEquals(1, chest.getInventory().getStackInSlot(1).getCount());
        verify(drop, never()).discard();
        assertEquals(8, drop.getItem().getCount());
        chest.handleEntity(drop);
        assertEquals(8, drop.getItem().getCount());
    }

    @Test
    void vatProgressReachesTheMenuAndSurvivesSavingWithoutTrackingClients(MinecraftServer server) {
        var level = mock(ServerLevel.class);
        when(level.registryAccess()).thenReturn(server.registryAccess());
        when(level.getGameTime()).thenReturn(1L);
        var recipes = mock(RecipeManager.class);
        when(level.getRecipeManager()).thenReturn(recipes);
        var recipe = mock(FermentingRecipe.class);
        when(recipe.matches(any(), same(level))).thenReturn(true);
        when(recipe.craft(any(), any())).thenReturn(List.of(OutputStack.of(new FluidStack(Fluids.WATER, 250))));
        when(recipe.ticks()).thenReturn(200);
        var holder = new RecipeHolder<>(ResourceLocation.withDefaultNamespace("test_vat"), recipe);
        doReturn(Optional.of(holder)).when(recipes).getRecipeFor(eq(EIORecipes.VAT_FERMENTING.type().get()),
            any(FermentingRecipe.Input.class), same(level));
        doReturn(Optional.of(holder)).when(recipes).byKey(holder.id());
        var pos = new BlockPos(10, 80, 10);
        var state = EIOBlocks.VAT.get().defaultBlockState();
        var vat = new VatBlockEntity(pos, state);
        vat.setLevel(level);
        vat.getInputTank().setFluid(new FluidStack(Fluids.WATER, 1000));
        VatBlockEntity.REAGENTS.get(0).setStackInSlot(vat, new ItemStack(Items.WHEAT_SEEDS));
        VatBlockEntity.REAGENTS.get(1).setStackInSlot(vat, new ItemStack(Items.WHEAT));
        vat.serverTick();
        var menu = new VatMenu(1, new Inventory(mock(Player.class)), vat);
        assertTrue(menu.getCraftingProgress() > 0);
        clearInvocations(level);
        vat.serverTick();
        verify(level).blockEntityChanged(pos);
        assertEquals(vat.getCraftingProgress(), menu.getCraftingProgress());
        assertTrue(vat.getUpdateTag(level.registryAccess()).getList(EnderBlockEntity.DATA, Tag.TAG_COMPOUND).isEmpty());

        var restored = new VatBlockEntity(pos, state);
        restored.loadWithComponents(vat.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
        restored.setLevel(level);
        restored.onLoad();
        assertEquals(menu.getCraftingProgress(), restored.getCraftingProgress());
    }
}
