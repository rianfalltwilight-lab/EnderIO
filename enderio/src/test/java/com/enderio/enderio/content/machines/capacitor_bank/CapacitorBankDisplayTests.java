package com.enderio.enderio.content.machines.capacitor_bank;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class CapacitorBankDisplayTests {
    @Test
    void barOcclusionAndSectionBoundaryInvalidateTheTopWithoutSteadyWorldQueries(MinecraftServer server) {
        var level = mock(Level.class);
        Map<BlockPos, CapacitorBankBlockEntity> banks = new HashMap<>();
        when(level.getBlockEntity(any())).thenAnswer(call -> banks.get(call.getArgument(0)));
        var topPos = new BlockPos(15, 80, -1);
        var top = bank(level, banks, topPos, DisplayMode.BAR);
        var bottom = bank(level, banks, topPos.below(), DisplayMode.BAR);
        var topDisplay = new CapacitorBankDisplay(level, topPos);
        var bottomDisplay = new CapacitorBankDisplay(level, bottom.getBlockPos());
        try {
            var bar = topDisplay.face(top, Direction.NORTH);
            assertEquals(2, bar.barLength());
            clearInvocations(level);
            assertSame(bar, topDisplay.face(top, Direction.NORTH));
            verifyNoInteractions(level);
            when(bottom.getDisplayMode(Direction.NORTH)).thenReturn(DisplayMode.NONE);
            CapacitorBankDisplay.blockChanged(level, bottom.getBlockPos().north());
            assertEquals(1, topDisplay.face(top, Direction.NORTH).barLength());
        } finally {
            bottomDisplay.removed();
            topDisplay.removed();
        }
    }

    @Test
    void rectangularIoFaceHasOneTextOwnerAndChangesWhenPeerIsRemoved(MinecraftServer server) {
        var level = mock(Level.class);
        Map<BlockPos, CapacitorBankBlockEntity> banks = new HashMap<>();
        when(level.getBlockEntity(any())).thenAnswer(call -> banks.get(call.getArgument(0)));
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 2; y++) {
                bank(level, banks, new BlockPos(x, y, 0), DisplayMode.IO);
            }
        }
        var displays = new HashMap<BlockPos, CapacitorBankDisplay>();
        banks.keySet().forEach(pos -> displays.put(pos, new CapacitorBankDisplay(level, pos)));
        try {
            assertEquals(1, displays.entrySet().stream().filter(entry ->
                entry.getValue().face(banks.get(entry.getKey()), Direction.NORTH).size().ownsText()).count());
            var removed = new BlockPos(3, 1, 0);
            displays.remove(removed).removed();
            banks.remove(removed);
            var size = displays.get(BlockPos.ZERO).face(banks.get(BlockPos.ZERO), Direction.NORTH).size();
            assertEquals(new CapacitorBankDisplay.Size(0, 0, 0, 0), size);
        } finally {
            displays.values().forEach(CapacitorBankDisplay::removed);
        }
    }

    private static CapacitorBankBlockEntity bank(Level level, Map<BlockPos, CapacitorBankBlockEntity> banks,
            BlockPos pos, DisplayMode mode) {
        var bank = mock(CapacitorBankBlockEntity.class);
        when(bank.getLevel()).thenReturn(level);
        when(bank.getBlockPos()).thenReturn(pos);
        when(bank.getDisplayMode(Direction.NORTH)).thenReturn(mode);
        banks.put(pos, bank);
        return bank;
    }
}
