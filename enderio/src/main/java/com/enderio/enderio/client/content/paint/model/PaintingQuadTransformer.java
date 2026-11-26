package com.enderio.enderio.client.content.paint.model;

import com.enderio.core.data.model.ModelHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record PaintingQuadTransformer(BlockState paint, @Nullable RenderType type) implements IQuadTransformer {
    private static final ThreadLocal<RandomSource> STATIC_MODEL_RANDOM =
            ThreadLocal.withInitial(() -> new SingleThreadedRandomSource(42L));

    @Override
    public void processInPlace(BakedQuad quad) {
        TextureAtlasSprite sprite = getSpriteForDirection(quad.getDirection());
        remapUV(quad.getVertices(), quad.getSprite(), sprite);
        quad.sprite = sprite;
    }

    static void remapUV(int[] vertices, TextureAtlasSprite original, TextureAtlasSprite sprite) {
        float originalU = original.getU0(), originalV = original.getV0();
        float targetU = sprite.getU0(), targetV = sprite.getV0();
        int width = sprite.contents().width(), height = sprite.contents().height();
        int originalWidth = original.contents().width(), originalHeight = original.contents().height();
        for (int i = 0; i < 4; i++) {
            int offset = IQuadTransformer.UV0 + i * IQuadTransformer.STRIDE;
            float u = Float.intBitsToFloat(vertices[offset]);
            float v = Float.intBitsToFloat(vertices[offset + 1]);
            vertices[offset] = Float.floatToRawIntBits((u - originalU) * width / originalWidth + targetU);
            vertices[offset + 1] = Float.floatToRawIntBits((v - originalV) * height / originalHeight + targetV);
        }
    }

    static RandomSource randomFor(BakedModel model) {
        // A custom model may reset, retain, or recursively use the supplied random source.
        return model.getClass() == SimpleBakedModel.class ? STATIC_MODEL_RANDOM.get() : RandomSource.create();
    }

    private TextureAtlasSprite getSpriteForDirection(Direction direction) {
        BakedModel model = getModel(paint);
        List<BakedQuad> quads = model.getQuads(paint, direction, randomFor(model), ModelData.EMPTY,
                type);
        return quads.isEmpty() ? ModelHelper.getMissingTexture() : quads.get(0).getSprite();
    }

    private BakedModel getModel(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }
}
