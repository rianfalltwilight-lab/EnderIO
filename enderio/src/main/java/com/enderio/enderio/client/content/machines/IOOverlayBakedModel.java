package com.enderio.enderio.client.content.machines;

import com.enderio.enderio.EnderIO;
import com.enderio.enderio.api.io.IOConfigurable;
import com.enderio.enderio.api.io.IOMode;
import com.enderio.enderio.client.foundation.model.ModelRenderUtil;
import com.enderio.enderio.foundation.block.entity.legacy.LegacyMachineBlockEntity;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class IOOverlayBakedModel implements IDynamicBakedModel {
    public static final ResourceLocation TEX_DISABLED = EnderIO.rl("block/overlay/disabled");
    public static final ResourceLocation TEX_PULL = EnderIO.rl("block/overlay/pull");
    public static final ResourceLocation TEX_PUSH = EnderIO.rl("block/overlay/push");
    public static final ResourceLocation TEX_PUSH_PULL = EnderIO.rl("block/overlay/push_pull");

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final IOMode[] MODES = IOMode.values();
    private static final ChunkRenderTypeSet CUTOUT_SET = ChunkRenderTypeSet.of(RenderType.cutout());
    private final BakedQuad[][] quads = new BakedQuad[DIRECTIONS.length][MODES.length];
    private final Int2ObjectOpenHashMap<List<BakedQuad>> overlays = new Int2ObjectOpenHashMap<>();

    public IOOverlayBakedModel(ModelState modelState) {
        this(material -> Minecraft.getInstance().getTextureAtlas(material.atlasLocation()).apply(material.texture()));
    }

    private IOOverlayBakedModel(Function<Material, TextureAtlasSprite> spriteGetter) {
        for (Direction direction : DIRECTIONS) {
            Vector3f[] vertices = ModelRenderUtil.createQuadVerts(direction, 0.0625f, 1 - 0.0625f, 1.0001f);
            for (IOMode mode : MODES) {
                if (mode == IOMode.NONE) {
                    continue;
                }

                ResourceLocation texture = switch (mode) {
                    case PUSH -> TEX_PUSH;
                    case PULL -> TEX_PULL;
                    case BOTH -> TEX_PUSH_PULL;
                    case DISABLED -> TEX_DISABLED;
                    default -> throw new IllegalStateException("Unexpected overlay mode " + mode);
                };
                var sprite = spriteGetter.apply(new Material(InventoryMenu.BLOCK_ATLAS, texture));
                quads[direction.ordinal()][mode.ordinal()] = ModelRenderUtil.createQuad(vertices, sprite);
            }
        }
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData extraData, @Nullable RenderType renderType) {
        if (side != null) {
            return List.of();
        }
        IOConfigurable config = extraData.get(LegacyMachineBlockEntity.IO_CONFIG_PROPERTY);
        if (config == null || !config.shouldRenderIOConfigOverlay()) {
            return List.of();
        }

        int key = 0;
        for (Direction direction : DIRECTIONS) {
            key |= config.getIOMode(direction).ordinal() << (3 * direction.ordinal());
        }
        if (key == 0) {
            return List.of();
        }
        return getOverlay(key);
    }

    // Chunk builders share baked models. Cache only configurations actually seen during this bake.
    private synchronized List<BakedQuad> getOverlay(int key) {
        var cached = overlays.get(key);
        if (cached == null) {
            var faces = new ArrayList<BakedQuad>(DIRECTIONS.length);
            for (Direction direction : DIRECTIONS) {
                var quad = quads[direction.ordinal()][(key >>> (3 * direction.ordinal())) & 7];
                if (quad != null) {
                    faces.add(quad);
                }
            }
            cached = List.copyOf(faces);
            overlays.put(key, cached);
        }
        return cached;
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return CUTOUT_SET;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return null;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    public static class Geometry implements IUnbakedGeometry<Geometry> {
        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
            return new IOOverlayBakedModel(spriteGetter);
        }
    }

    public static class Loader implements IGeometryLoader<Geometry> {
        @Override
        public Geometry read(JsonObject modelContents, JsonDeserializationContext deserializationContext) {
            return new Geometry();
        }
    }

}
