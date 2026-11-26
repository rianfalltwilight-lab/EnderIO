package com.enderio.enderio.tests.client;

import com.enderio.enderio.api.io.IOConfigurable;
import com.enderio.enderio.api.io.IOMode;
import com.enderio.enderio.client.content.machines.IOOverlayBakedModel;
import com.enderio.enderio.foundation.block.entity.legacy.LegacyMachineBlockEntity;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IOOverlayModelTests {
    @Test
    void overlayFollowsConfigurationAndIsEmittedOnlyOnce() {
        var sprite = mock(TextureAtlasSprite.class);
        var contents = mock(SpriteContents.class);
        when(sprite.contents()).thenReturn(contents);
        when(contents.width()).thenReturn(16);
        when(contents.height()).thenReturn(16);
        var model = (IOOverlayBakedModel) new IOOverlayBakedModel.Geometry()
            .bake(null, null, material -> sprite, null, ItemOverrides.EMPTY);
        var config = mock(IOConfigurable.class);
        when(config.shouldRenderIOConfigOverlay()).thenReturn(true);
        when(config.getIOMode(any())).thenReturn(IOMode.NONE);
        when(config.getIOMode(Direction.NORTH)).thenReturn(IOMode.PUSH);
        var data = ModelData.builder().with(LegacyMachineBlockEntity.IO_CONFIG_PROPERTY, config).build();
        var random = RandomSource.create(1);

        for (var side : Direction.values()) {
            assertTrue(model.getQuads(null, side, random, data, null).isEmpty());
        }
        var first = model.getQuads(null, null, random, data, null);
        assertEquals(1, first.size());
        assertSame(first, model.getQuads(null, null, random, data, null));

        when(config.getIOMode(Direction.UP)).thenReturn(IOMode.PULL);
        var next = model.getQuads(null, null, random, data, null);
        assertEquals(2, next.size());
        assertTrue(next.contains(first.getFirst()));
        when(config.shouldRenderIOConfigOverlay()).thenReturn(false);
        assertTrue(model.getQuads(null, null, random, data, null).isEmpty());
    }
}
