package com.enderio.enderio.tests.content.fire_crafting;

import com.enderio.enderio.content.fire_crafting.FireCraftingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.EnumSet;

import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class FireCraftingEventsTests {
    @Test
    void unrelatedNeighborUpdatesDoNotCreateATrackerOrReadTheWorld() {
        var level = mock(ServerLevel.class);
        var event = new BlockEvent.NeighborNotifyEvent(level, BlockPos.ZERO, Blocks.STONE.defaultBlockState(),
            EnumSet.of(Direction.EAST), false);
        FireCraftingManager.onNeighborNotifyEvent(event);
        verify(level, never()).getData(FireCraftingManager.ATTACHMENT_TYPE);
        verify(level, never()).getBlockState(any());

        when(level.hasData(FireCraftingManager.ATTACHMENT_TYPE)).thenReturn(true);
        when(level.getData(FireCraftingManager.ATTACHMENT_TYPE)).thenReturn(new FireCraftingManager());
        FireCraftingManager.onNeighborNotifyEvent(event);
        verify(level, never()).getBlockState(any());
    }
}
