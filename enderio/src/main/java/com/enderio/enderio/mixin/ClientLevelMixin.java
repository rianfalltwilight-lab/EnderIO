package com.enderio.enderio.mixin;

import com.enderio.enderio.content.machines.capacitor_bank.CapacitorBankDisplay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    // Client block packets suppress neighbor shape propagation. The front block still affects the whole display.
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void enderio$updateBankOcclusion(BlockPos pos, BlockState previous, BlockState current, CallbackInfo ci) {
        CapacitorBankDisplay.blockChanged((ClientLevel) (Object) this, pos);
    }
}
