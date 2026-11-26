package com.enderio.enderio.tests.foundation.block;

import com.enderio.enderio.content.machines.drain.DrainBlockEntity;
import com.enderio.enderio.foundation.attachment.ActionRange;
import com.enderio.enderio.init.EIOBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class DrainTests {
    @Test
    void scanUsesVanillaOrderKeepsItsBudgetAndRestartsAfterTakingTheIntake(MinecraftServer server) {
        var level = mock(ServerLevel.class);
        var pos = new BlockPos(-7, 80, 9);
        var intake = pos.below();
        var drain = new DrainBlockEntity(pos, EIOBlocks.DRAIN.get().defaultBlockState());
        var oldSave = new CompoundTag();
        oldSave.putInt(DrainBlockEntity.CONSUMED, 1500);
        drain.loadAdditional(oldSave, server.registryAccess());
        drain.setLevel(level);
        drain.setActionRange(new ActionRange(2, false));
        var sources = new HashSet<BlockPos>();
        sources.add(intake);
        var visited = new ArrayList<BlockPos>();
        when(level.getFluidState(any())).thenAnswer(invocation -> {
            BlockPos read = invocation.getArgument(0);
            visited.add(read.immutable());
            return (sources.contains(read) ? Fluids.WATER : Fluids.EMPTY).defaultFluidState();
        });
        assertTrue(drain.isActive());
        visited.clear();
        for (int tick = 0; tick < 63; tick++) {
            int before = visited.size();
            drain.drainFluids();
            assertTrue(visited.size() - before <= 2);
        }
        var expected = new ArrayList<BlockPos>();
        for (BlockPos candidate : BlockPos.betweenClosed(pos.offset(-2, -5, -2), pos.offset(2, -1, 2))) {
            if (!candidate.equals(intake)) {
                expected.add(candidate.immutable());
            }
        }
        expected.add(intake);
        assertEquals(expected, visited);
        verify(level).setBlock(intake, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertEquals(1000, drain.getFluidTank().getFluidAmount());

        // A full tank must keep the first candidate for retry, even when the scan just wrapped.
        sources.add(expected.getFirst());
        drain.getFluidTank().setFluid(new FluidStack(Fluids.WATER, 3000));
        visited.clear();
        drain.drainFluids();
        drain.drainFluids();
        assertEquals(java.util.List.of(expected.getFirst(), expected.getFirst()), visited);
        drain.setActionRange(new ActionRange(0, false));
        visited.clear();
        drain.drainFluids();
        assertTrue(visited.isEmpty());
    }
}
