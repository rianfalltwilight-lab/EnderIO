package com.enderio.enderio.tests.foundation.block;

import com.enderio.enderio.content.machines.sag_mill.SagMillBlockEntity;
import com.enderio.enderio.foundation.state.MachineState;
import com.enderio.enderio.init.EIOBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class MachineStateUpdateTests {
    @Test
    void onlyStateTransitionsNotifyTheLevel() {
        var pos = new BlockPos(1, 80, 1);
        var state = EIOBlocks.SAG_MILL.get().defaultBlockState();
        var machine = new SagMillBlockEntity(pos, state) {
            void updateActive(boolean active) {
                updateMachineState(MachineState.ACTIVE, active);
            }
        };
        var level = mock(ServerLevel.class);
        machine.setLevel(level);
        clearInvocations(level);

        machine.updateActive(true);
        machine.updateActive(true);
        assertTrue(machine.getMachineStates().contains(MachineState.ACTIVE));
        verify(level).sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
        machine.updateActive(false);
        machine.updateActive(false);
        assertFalse(machine.getMachineStates().contains(MachineState.ACTIVE));
        verify(level, times(2)).sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }
}
