package com.enderio.enderio.tests.client;

import com.enderio.enderio.client.SoundHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SoundHandlerTests {
    @Test
    void inactiveSoundsAndLogoutReleasePositionEntries() {
        var first = new BlockPos(1, 2, 3);
        var second = first.east();
        var sound = mock(SoundInstance.class);
        var manager = mock(SoundManager.class);
        var mc = mock(Minecraft.class);
        when(mc.getSoundManager()).thenReturn(manager);
        try (var client = mockStatic(Minecraft.class)) {
            client.when(Minecraft::getInstance).thenReturn(mc);
            SoundHandler.SOUND_MAP.put(first.asLong(), sound);
            SoundHandler.SOUND_MAP.put(second.asLong(), sound);
            SoundHandler.stopSound(first);
            assertFalse(SoundHandler.SOUND_MAP.containsKey(first.asLong()));
            assertTrue(SoundHandler.SOUND_MAP.containsKey(second.asLong()));
            verify(manager, never()).stop(sound);
            SoundHandler.onLogout(mock(ClientPlayerNetworkEvent.LoggingOut.class));
            assertTrue(SoundHandler.SOUND_MAP.isEmpty());
        } finally {
            SoundHandler.SOUND_MAP.clear();
        }
    }
}
