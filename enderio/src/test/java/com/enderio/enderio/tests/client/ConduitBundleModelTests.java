package com.enderio.enderio.tests.client;

import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.bundle.ConduitBundle;
import com.enderio.enderio.api.conduits.connection.ConnectionStatus;
import com.enderio.enderio.api.conduits.model.ConduitModelModifier;
import com.enderio.enderio.client.content.conduits.model.ConduitAdditionalModels;
import com.enderio.enderio.client.content.conduits.model.bundle.ConduitBundleModel;
import com.enderio.enderio.client.content.conduits.model.bundle.ConduitBundleRenderState;
import com.enderio.enderio.client.content.conduits.model.modifier.ConduitModelModifiers;
import com.enderio.enderio.client.content.conduits.model.modifier.RedstoneConduitModelModifier;
import com.enderio.enderio.content.conduits.type.redstone.RedstoneConduit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.enderio.enderio.client.content.conduits.model.ConduitAdditionalModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class ConduitBundleModelTests {
    private static final List<ModelResourceLocation> PARTS = List.of(CONDUIT_CONNECTOR, CONDUIT_FACADE_OVERLAY, CONDUIT_CONNECTION,
        CONDUIT_CORE, BOX, CONDUIT_CONNECTION_BOX, CONDUIT_IO_IN, CONDUIT_IO_IN_OUT, CONDUIT_IO_OUT, CONDUIT_IO_REDSTONE);

    @Test
    void staticBakesMatchAllCullFacesAndRefreshAfterResourceAndVisualChanges() {
        var inactive = ResourceLocation.parse("enderio:test_inactive");
        var active = ResourceLocation.parse("enderio:test_active");
        Holder<Conduit<?, ?>> conduit = Holder.direct(new RedstoneConduit(inactive, active, Component.empty()));
        var type = conduit.value().type();
        var bundle = mock(ConduitBundle.class);
        when(bundle.getConduits()).thenReturn(List.of(conduit));
        when(bundle.getConnectedConduits(Direction.NORTH)).thenReturn(List.of(conduit));
        when(bundle.getConnectionStatus(eq(conduit), any())).thenReturn(ConnectionStatus.DISCONNECTED);
        when(bundle.getConduitExtraWorldData(conduit)).thenReturn(new CompoundTag());
        var snapshot = ConduitBundleRenderState.of(bundle);
        var data = ModelData.builder().with(ConduitBundleRenderState.PROPERTY, snapshot).build();
        var model = new ConduitBundleModel();
        var reads = new AtomicInteger();
        var oldSprite = sprite(0.25f);
        var activeSprite = sprite(0.5f);
        var base = staticModel(oldSprite, reads, 10);
        var parts = new HashMap<ModelResourceLocation, BakedModel>();
        var previous = new HashMap<ModelResourceLocation, BakedModel>();
        for (var key : PARTS) {
            parts.put(key, base);
            previous.put(key, modelOf(key));
        }

        var client = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        var atlas = mock(TextureAtlas.class);
        when(client.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS)).thenReturn(atlas);
        when(atlas.getSprite(inactive)).thenReturn(oldSprite);
        when(atlas.getSprite(active)).thenReturn(activeSprite);
        try (var mc = mockStatic(Minecraft.class); var modifiers = mockStatic(ConduitModelModifiers.class)) {
            mc.when(Minecraft::getInstance).thenReturn(client);
            modifiers.when(() -> ConduitModelModifiers.getModifier(type)).thenReturn(new RedstoneConduitModelModifier());

            // A custom part disables the cache and provides the reference output for every cull face.
            parts.put(BOX, mock(BakedModel.class));
            reload(parts);
            var sides = Arrays.asList(null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
            var reference = sides.stream().map(side -> model.getQuads(null, side, RandomSource.create(1), data, null)).toList();
            assertTrue(reference.stream().allMatch(quads -> quads.size() == 2));
            assertEquals(14, reads.get());

            parts.put(BOX, base);
            reload(parts);
            for (int i = 0; i < sides.size(); i++) {
                var fresh = model.getQuads(null, sides.get(i), RandomSource.create(2), data, null);
                assertQuadsEqual(reference.get(i), fresh);
                fresh.getFirst().getVertices()[IQuadTransformer.COLOR] = -1;
                fresh.clear();
                int readsBeforeHit = reads.get();
                var hit = model.getQuads(null, sides.get(i), RandomSource.create(3), data, null);
                assertQuadsEqual(reference.get(i), hit);
                assertEquals(readsBeforeHit, reads.get());
            }
            assertEquals(28, reads.get());

            snapshot.getExtraWorldData(conduit).putBoolean("IsActive", true);
            var lit = model.getQuads(null, null, RandomSource.create(4), data, null);
            assertSame(activeSprite, lit.getFirst().getSprite());
            int uv = IQuadTransformer.UV0;
            assertEquals(Float.floatToRawIntBits(0.625f), lit.getFirst().getVertices()[uv]);

            var reloadBase = staticModel(oldSprite, reads, 90);
            parts.replaceAll((key, old) -> reloadBase);
            reload(parts);
            var reloaded = model.getQuads(null, null, RandomSource.create(5), data, null);
            assertEquals(90, reloaded.getFirst().getVertices()[IQuadTransformer.COLOR]);

            var custom = mock(ConduitModelModifier.class);
            when(custom.getTexture(eq(conduit), any())).thenReturn(active);
            when(custom.createConnectionQuads(eq(conduit), any(), isNull(), eq(Direction.NORTH), any(), isNull()))
                .thenAnswer(call -> List.of(quad(oldSprite, call.<RandomSource>getArgument(4).nextInt())));
            modifiers.when(() -> ConduitModelModifiers.getModifier(type)).thenReturn(custom);
            for (int seed : List.of(1, 2)) {
                var actual = RandomSource.create(seed);
                var expected = RandomSource.create(seed);
                var quads = model.getQuads(null, null, actual, data, null);
                assertEquals(3, quads.size());
                assertEquals(expected.nextInt(), quads.get(1).getVertices()[IQuadTransformer.COLOR]);
                assertEquals(expected.nextLong(), actual.nextLong());
            }
            assertSame(data, model.getModelData(null, null, null, data));
        } finally {
            reload(previous);
        }
    }

    private static void reload(Map<ModelResourceLocation, BakedModel> parts) {
        ConduitAdditionalModels.bakingModelsFinished(new ModelEvent.BakingCompleted(null, parts, null));
    }

    private static TextureAtlasSprite sprite(float origin) {
        var sprite = mock(TextureAtlasSprite.class);
        var contents = mock(SpriteContents.class);
        when(sprite.contents()).thenReturn(contents);
        when(contents.width()).thenReturn(16);
        when(contents.height()).thenReturn(16);
        when(sprite.getU0()).thenReturn(origin);
        when(sprite.getV0()).thenReturn(origin);
        return sprite;
    }

    private static SimpleBakedModel staticModel(TextureAtlasSprite sprite, AtomicInteger reads, int id) {
        var faces = new EnumMap<Direction, List<BakedQuad>>(Direction.class);
        for (var side : Direction.values()) {
            faces.put(side, countedQuad(sprite, reads, id + side.ordinal() + 1));
        }
        return new SimpleBakedModel(countedQuad(sprite, reads, id), faces, true, true, true, sprite,
            ItemTransforms.NO_TRANSFORMS, ItemOverrides.EMPTY);
    }

    private static List<BakedQuad> countedQuad(TextureAtlasSprite sprite, AtomicInteger reads, int id) {
        var quad = quad(sprite, id);
        return new AbstractList<>() {
            @Override
            public BakedQuad get(int index) {
                reads.incrementAndGet();
                return quad;
            }

            @Override
            public int size() {
                return 1;
            }
        };
    }

    private static BakedQuad quad(TextureAtlasSprite sprite, int id) {
        var vertices = new int[4 * IQuadTransformer.STRIDE];
        for (int i = 0; i < 4; i++) {
            int vertex = i * IQuadTransformer.STRIDE;
            vertices[vertex + IQuadTransformer.COLOR] = id;
            vertices[vertex + IQuadTransformer.UV0] = Float.floatToRawIntBits(0.375f);
            vertices[vertex + IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits(0.375f);
        }
        return new BakedQuad(vertices, -1, Direction.NORTH, sprite, true, false);
    }

    private static void assertQuadsEqual(List<BakedQuad> expected, List<BakedQuad> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            var left = expected.get(i);
            var right = actual.get(i);
            assertArrayEquals(left.getVertices(), right.getVertices());
            assertSame(left.getSprite(), right.getSprite());
            assertEquals(left.getDirection(), right.getDirection());
            assertEquals(left.getTintIndex(), right.getTintIndex());
            assertEquals(left.isShade(), right.isShade());
            assertEquals(left.hasAmbientOcclusion(), right.hasAmbientOcclusion());
        }
    }
}
