package com.enderio.enderio.tests.client;

import com.enderio.enderio.client.content.conduits.model.ConduitAdditionalModels;
import com.enderio.enderio.client.content.conduits.model.facades.FacadeItemModel;
import com.enderio.enderio.client.content.paint.model.PaintedBlockModel;
import com.enderio.enderio.content.paint.BlockPaintData;
import com.enderio.enderio.content.paint.block.entity.SinglePaintedBlockEntity;
import com.enderio.enderio.init.EIODataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class PaintedModelTests {
    private static TextureAtlasSprite sprite(float u, float v, int width, int height) {
        var sprite = mock(TextureAtlasSprite.class);
        var contents = mock(SpriteContents.class);
        when(sprite.contents()).thenReturn(contents);
        when(contents.width()).thenReturn(width);
        when(contents.height()).thenReturn(height);
        when(sprite.getU0()).thenReturn(u);
        when(sprite.getV0()).thenReturn(v);
        return sprite;
    }

    private static SimpleBakedModel cube(BakedQuad face) {
        return new SimpleBakedModel(List.of(face), Map.of(Direction.NORTH, List.of(face)),
            true, true, true, face.getSprite(), ItemTransforms.NO_TRANSFORMS, ItemOverrides.EMPTY, RenderTypeGroup.EMPTY);
    }

    @Test
    void bakedPaintPreservesUvAndUpdatesWhenThePaintModelOrRenderLayerChanges() {
        var mc = mock(Minecraft.class);
        var renderer = mock(BlockRenderDispatcher.class);
        when(mc.getBlockRenderer()).thenReturn(renderer);
        var original = sprite(0.25f, 0.5f, 16, 32);
        var gold = sprite(0.625f, 0.25f, 32, 16);
        int[] vertices = new int[4 * IQuadTransformer.STRIDE];
        vertices[IQuadTransformer.UV0] = Float.floatToRawIntBits(0.375f);
        vertices[IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits(0.75f);
        var shape = new BakedQuad(vertices, -1, Direction.NORTH, original, true);
        var tinted = new BakedQuad(vertices.clone(), 0, Direction.NORTH, gold, true);
        var state = Blocks.STONE.defaultBlockState();
        var paint = Blocks.GOLD_BLOCK.defaultBlockState();
        when(renderer.getBlockModel(state)).thenReturn(cube(shape));
        when(renderer.getBlockModel(paint)).thenReturn(cube(tinted));
        var data = ModelData.builder().with(SinglePaintedBlockEntity.PAINT, Blocks.GOLD_BLOCK).build();
        var random = RandomSource.create(42);
        var solid = RenderType.solid();
        var cutout = RenderType.cutout();
        try (var instance = mockStatic(Minecraft.class)) {
            instance.when(Minecraft::getInstance).thenReturn(mc);
            var model = new PaintedBlockModel(Blocks.STONE, null);
            var first = model.getQuads(state, Direction.NORTH, random, data, solid);
            assertSame(first, model.getQuads(state, Direction.NORTH, random, data, solid));
            var face = first.getFirst();
            assertSame(gold, face.getSprite());
            assertEquals(1, face.getTintIndex());
            assertEquals(0.875f, Float.intBitsToFloat(face.getVertices()[IQuadTransformer.UV0]));
            assertEquals(0.375f, Float.intBitsToFloat(face.getVertices()[IQuadTransformer.UV0 + 1]));
            assertEquals(0.375f, Float.intBitsToFloat(vertices[IQuadTransformer.UV0]));

            var dynamic = mock(BakedModel.class);
            when(dynamic.getQuads(paint, Direction.NORTH, random, ModelData.EMPTY, solid))
                .thenReturn(List.of(tinted), List.of(shape));
            when(dynamic.getQuads(paint, Direction.NORTH, random, ModelData.EMPTY, cutout)).thenReturn(List.of(shape));
            when(renderer.getBlockModel(paint)).thenReturn(dynamic);
            assertSame(gold, model.getQuads(state, Direction.NORTH, random, data, solid).getFirst().getSprite());
            assertSame(original, model.getQuads(state, Direction.NORTH, random, data, solid).getFirst().getSprite());
            assertSame(original, model.getQuads(state, Direction.NORTH, random, data, cutout).getFirst().getSprite());
        }
    }

    @Test
    void facadePassReusesStaticFacesAndLeavesDynamicOverridesLive() {
        var mc = mock(Minecraft.class);
        var renderer = mock(ItemRenderer.class);
        when(mc.getItemRenderer()).thenReturn(renderer);
        var sprite = sprite(0, 0, 16, 16);
        var face = new BakedQuad(new int[4 * IQuadTransformer.STRIDE], -1, Direction.NORTH, sprite, true);
        var cube = cube(face);
        var layer = RenderType.solid();
        var random = RandomSource.create(42);
        var stack = new ItemStack(Items.STONE);
        stack.set(EIODataComponents.BLOCK_PAINT, new BlockPaintData(Blocks.GOLD_BLOCK));
        try (var instance = mockStatic(Minecraft.class); var models = mockStatic(ConduitAdditionalModels.class)) {
            instance.when(Minecraft::getInstance).thenReturn(mc);
            models.when(() -> ConduitAdditionalModels.modelOf(ConduitAdditionalModels.CONDUIT_FACADE_OVERLAY)).thenReturn(cube);
            when(renderer.getModel(any(), isNull(), isNull(), eq(0))).thenReturn(cube);
            var root = new FacadeItemModel(cube);
            var pass = root.getRenderPasses(stack, false).getFirst();
            var first = pass.getQuads(null, Direction.NORTH, random, ModelData.EMPTY, layer);
            assertEquals(List.of(face, face), first);
            assertSame(first, pass.getQuads(null, Direction.NORTH, random, ModelData.EMPTY, layer));
            var dynamic = mock(BakedModel.class);
            when(dynamic.getQuads(Blocks.GOLD_BLOCK.defaultBlockState(), Direction.NORTH, random, ModelData.EMPTY, layer))
                .thenReturn(List.of(), List.of(face));
            when(renderer.getModel(any(), isNull(), isNull(), eq(0))).thenReturn(dynamic);
            assertEquals(List.of(face), pass.getQuads(null, Direction.NORTH, random, ModelData.EMPTY, layer));
            assertEquals(first, pass.getQuads(null, Direction.NORTH, random, ModelData.EMPTY, layer));
        }
    }
}
