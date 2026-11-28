package com.enderio.enderio.foundation.task;

import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

public interface MachineTask extends INBTSerializable<CompoundTag> {
    void tick();

    float getProgress();

    boolean isCompleted();

    /** Whether a completed task actually did its job, rather than being abandoned. */
    default boolean didWork() {
        return true;
    }
}
