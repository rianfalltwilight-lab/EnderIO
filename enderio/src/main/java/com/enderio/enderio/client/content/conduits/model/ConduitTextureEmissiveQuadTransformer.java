package com.enderio.enderio.client.content.conduits.model;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.QuadTransformers;

public record ConduitTextureEmissiveQuadTransformer(TextureAtlasSprite newSprite, int lightLevel) implements IQuadTransformer {

    @Override
    public void processInPlace(BakedQuad quad) {
        if (lightLevel != 0) {
            QuadTransformers.settingEmissivity(lightLevel).processInPlace(quad);
        }

        var vertices = quad.getVertices();
        var oldSprite = quad.getSprite();
        var oldSize = oldSprite.contents();
        var newSize = newSprite.contents();
        for (int i = 0; i < 4; i++) {
            int uv = i * STRIDE + UV0;
            float u = (Float.intBitsToFloat(vertices[uv]) - oldSprite.getU0()) * newSize.width() / oldSize.width() + newSprite.getU0();
            float v = (Float.intBitsToFloat(vertices[uv + 1]) - oldSprite.getV0()) * newSize.height() / oldSize.height() + newSprite.getV0();
            vertices[uv] = Float.floatToRawIntBits(u);
            vertices[uv + 1] = Float.floatToRawIntBits(v);
        }
        quad.sprite = newSprite;
    }

}
