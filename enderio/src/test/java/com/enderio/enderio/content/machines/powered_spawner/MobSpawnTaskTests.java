package com.enderio.enderio.content.machines.powered_spawner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class MobSpawnTaskTests {
    @Test
    void scansChunkEntriesIncludingBothFacesOfTheOldBlockRange() {
        var level = mock(ServerLevel.class);
        var first = mock(LevelChunk.class);
        var second = mock(LevelChunk.class);
        var lower = new BlockPos(-3, 70, 15);
        var upper = new BlockPos(4, 73, 15);
        var above = upper.above();
        var outside = lower.west();
        var ordinary = lower.above();
        when(level.getChunk(-1, 0)).thenReturn(first);
        when(level.getChunk(0, 0)).thenReturn(second);
        when(first.getBlockEntitiesPos()).thenReturn(Set.of(lower, ordinary, outside));
        when(second.getBlockEntitiesPos()).thenReturn(Set.of(upper, above));
        var spawner = mock(PoweredSpawnerBlockEntity.class);
        when(level.getBlockEntity(lower)).thenReturn(spawner);
        when(level.getBlockEntity(upper)).thenReturn(spawner);

        assertEquals(2, MobSpawnTask.countSpawners(level, new AABB(-2.5, 70, 15, 4, 73, 15.9)));
        verify(level, times(3)).getBlockEntity(any(BlockPos.class));
        verify(level, never()).getBlockEntity(above);
        verify(level, never()).getBlockEntity(outside);
    }
}
