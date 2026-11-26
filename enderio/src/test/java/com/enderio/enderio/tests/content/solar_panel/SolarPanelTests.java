package com.enderio.enderio.tests.content.solar_panel;

import com.enderio.enderio.api.soul.Soul;
import com.enderio.enderio.content.machines.solar_panel.ISolarPanelTier;
import com.enderio.enderio.content.machines.solar_panel.SolarPanelBlockEntity;
import com.enderio.enderio.content.machines.solar_panel.SolarPanelNode;
import com.enderio.enderio.content.machines.solar_panel.SolarPanelTier;
import com.enderio.enderio.foundation.souldata.SolarSoul;
import com.enderio.enderio.foundation.tag.EIOTags;
import com.enderio.enderio.init.EIOBlockEntities;
import com.enderio.enderio.init.EIOBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class SolarPanelTests {
    private static SolarPanelBlockEntity panel(ISolarPanelTier tier) {
        var panel = mock(SolarPanelBlockEntity.class);
        when(panel.tier()).thenReturn(tier);
        return panel;
    }

    @Test
    void overflowOnlyStoresTheGeneratedAmountAndStopsWhenFull() {
        var tier = mock(ISolarPanelTier.class);
        when(tier.getStorageCapacity()).thenReturn(20);
        var source = panel(tier);
        var a = new SolarPanelNode(source);
        var b = new SolarPanelNode(panel(tier));
        var c = new SolarPanelNode(panel(tier));
        a.getNetwork().connect(a, b);
        a.getNetwork().connect(b, c);
        a.setEnergyStored(20);

        a.addEnergyToNetwork(10);
        assertEquals(10, b.getEnergyStored() + c.getEnergyStored());
        assertEquals(30, a.getNetwork().getTotalEnergyStored());
        verify(source, never()).setChanged();

        a.addEnergyToNetwork(100);
        assertEquals(60, a.getNetwork().getTotalEnergyStored());
        clearInvocations(tier);
        a.addEnergyToNetwork(10);
        verify(tier, times(1)).getStorageCapacity(); // One distinct tier for all three full panels.
        assertEquals(60, a.getNetwork().getTotalEnergyStored());

        assertEquals(5, a.extractEnergy(5, true));
        assertEquals(0, a.extractEnergy(0, false));
        verify(source, never()).setChanged();
        assertEquals(5, a.extractEnergy(5, false));
        b.addEnergyToNetwork(3);
        assertEquals(58, a.getNetwork().getTotalEnergyStored());

        a.addEnergyToNetwork(10);
        when(tier.getStorageCapacity()).thenReturn(30);
        c.addEnergyToNetwork(7);
        assertEquals(67, a.getNetwork().getTotalEnergyStored());
    }

    @Test
    void storedEnergyFollowsRestoresMergesSplitsAndRemoval() {
        var tier = mock(ISolarPanelTier.class);
        when(tier.getStorageCapacity()).thenReturn(20);
        var a = new SolarPanelNode(panel(tier));
        var b = new SolarPanelNode(panel(tier));
        var c = new SolarPanelNode(panel(tier));
        a.setEnergyStored(12);
        b.setEnergyStored(4);
        c.setEnergyStored(7);
        a.getNetwork().connect(a, b);
        a.getNetwork().connect(b, c);
        b.setEnergyStored(6);
        assertEquals(25, c.getNetwork().getTotalEnergyStored());

        a.getNetwork().disconnect(b, c);
        assertEquals(18, a.getNetwork().getTotalEnergyStored());
        assertEquals(7, c.getNetwork().getTotalEnergyStored());
        a.getNetwork().remove(b);
        assertEquals(12, a.getNetwork().getTotalEnergyStored());
        a.addEnergyToNetwork(50);
        var fresh = new SolarPanelNode(panel(tier));
        a.getNetwork().connect(a, fresh);
        a.addEnergyToNetwork(4);
        assertEquals(24, a.getNetwork().getTotalEnergyStored());
    }

    @Test
    void canopyAndAdjacentFluidsTakeEffectOnTheNextQuery() {
        var pos = new BlockPos(3, 80, 3);
        var tier = mock(ISolarPanelTier.class);
        when(tier.getProductionRate()).thenReturn(40);
        var panel = new SolarPanelBlockEntity(EIOBlockEntities.SOLAR_PANELS.get(SolarPanelTier.ENERGETIC).get(),
                pos, EIOBlocks.SOLAR_PANELS.get(SolarPanelTier.ENERGETIC).get().defaultBlockState(), tier);
        var level = mock(ServerLevel.class);
        var dimension = mock(DimensionType.class);
        when(dimension.hasSkyLight()).thenReturn(true);
        when(level.dimensionType()).thenReturn(dimension);
        when(level.getDayTime()).thenReturn(6000L);
        when(level.canSeeSky(pos.above())).thenReturn(true);
        when(level.getFluidState(any())).thenReturn(Fluids.EMPTY.defaultFluidState());
        panel.setLevel(level);

        assertEquals(40, panel.getGenerationRate());
        verify(level, never()).getFluidState(any());
        when(level.canSeeSky(pos.above())).thenReturn(false);
        assertEquals(0, panel.getGenerationRate());
        FluidState light = mock(FluidState.class);
        when(light.is(EIOTags.Fluids.SOLAR_PANEL_LIGHT)).thenReturn(true);
        when(level.getFluidState(pos.east())).thenReturn(light);
        assertEquals(40, panel.getGenerationRate());
        when(level.getFluidState(pos.east())).thenReturn(Fluids.EMPTY.defaultFluidState());
        assertEquals(0, panel.getGenerationRate());

        var soul = Soul.of(EntityType.PHANTOM);
        var id = soul.entityTypeId();
        var beforeReload = SolarSoul.SOLAR.map;
        try {
            SolarSoul.SOLAR.replaceData(Map.of(id, new SolarSoul.SoulData(id, true, true, Optional.empty())));
            panel.bindSoul(soul);
            assertEquals(40, panel.getGenerationRate());
            SolarSoul.SOLAR.replaceData(Map.of());
            assertEquals(0, panel.getGenerationRate());
            panel.bindSoul(Soul.EMPTY);
            assertTrue(panel.getBoundSoul().isEmpty());
        } finally {
            SolarSoul.SOLAR.replaceData(beforeReload);
        }
    }
}
