package com.enderio.enderio.client.content.conduits.model.bundle;

import com.enderio.core.data.model.ModelHelper;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.model.ConduitModelModifier;
import com.enderio.enderio.client.content.conduits.model.ConduitTextureEmissiveQuadTransformer;
import com.enderio.enderio.client.content.conduits.model.facades.ClientFacadeVisibility;
import com.enderio.enderio.client.content.conduits.model.modifier.ConduitModelModifiers;
import com.enderio.enderio.client.content.conduits.model.modifier.RedstoneConduitModelModifier;
import com.enderio.enderio.client.foundation.model.BoxTextureQuadTransformer;
import com.enderio.enderio.client.foundation.model.ColorQuadTransformer;
import com.enderio.enderio.content.conduits.type.energy.EnergyConduit;
import com.enderio.enderio.content.conduits.type.fluid.FluidConduit;
import com.enderio.enderio.content.conduits.type.item.ItemConduit;
import com.enderio.enderio.content.conduits.type.redstone.RedstoneConduit;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.math.Axis;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.enderio.enderio.client.content.conduits.model.ConduitAdditionalModels.*;

public class ConduitBundleModel implements IDynamicBakedModel {
    public static final ModelProperty<ModelData> FACADE_MODEL_DATA = new ModelProperty<>();

    private static final ChunkRenderTypeSet CUTOUT_SET = ChunkRenderTypeSet.of(RenderType.cutout());

    private static final IQuadTransformer[] ROTATIONS = Arrays.stream(Direction.values())
            .map(direction -> transformer(rotateTransformation(direction))).toArray(IQuadTransformer[]::new);
    private static final Cache<Vec3i, IQuadTransformer> TRANSLATIONS = CacheBuilder.newBuilder().maximumSize(32).build();

    private record QuadKey(ConduitBundleRenderState.Layout layout, Map<Holder<Conduit<?, ?>>, ResourceLocation> textures,
                           @Nullable Direction side, @Nullable RenderType layer) {}
    private static volatile @Nullable Cache<QuadKey, List<BakedQuad>> quadCache;

    public static void onModelsBaked(boolean staticModels) {
        quadCache = staticModels ? CacheBuilder.newBuilder().maximumSize(128).build() : null;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData extraData, @Nullable RenderType renderType) {
        var bundleState = extraData.get(ConduitBundleRenderState.PROPERTY);
        if (bundleState == null || (bundleState.hasFacade() && bundleState.doesFacadeHideConduits() && ClientFacadeVisibility.areFacadesVisible())) {
            return new ArrayList<>();
        }

        var cache = quadCache;
        if (cache != null) {
            var textures = cacheableTextures(bundleState);
            if (textures != null) {
                var key = new QuadKey(bundleState.layout(), textures, side, renderType);
                var baked = cache.getIfPresent(key);
                if (baked == null) {
                    baked = bakeQuads(bundleState, state, side, rand, extraData, renderType, textures);
                    cache.put(key, baked);
                }
                // Renderers may edit the list or its vertices. Keep the baked template private.
                List<BakedQuad> output = new ArrayList<>(baked.size());
                for (var quad : baked) {
                    output.add(QuadTransformers.empty().process(quad));
                }
                return output;
            }
        }
        return bakeQuads(bundleState, state, side, rand, extraData, renderType, null);
    }

    private static @Nullable Map<Holder<Conduit<?, ?>>, ResourceLocation> cacheableTextures(ConduitBundleRenderState bundle) {
        Map<Holder<Conduit<?, ?>>, ResourceLocation> textures = new HashMap<>();
        for (var holder : bundle.conduits()) {
            var conduit = holder.value();
            var modifier = ConduitModelModifiers.getModifier(conduit.type());
            if (!(conduit instanceof ItemConduit || conduit instanceof FluidConduit || conduit instanceof EnergyConduit || conduit instanceof RedstoneConduit)
                    || (modifier != null && modifier.getClass() != RedstoneConduitModelModifier.class)) {
                return null;
            }
            textures.put(holder, modifier == null ? conduit.texture() : modifier.getTexture(holder, bundle.getExtraWorldData(holder)));
        }
        return textures;
    }

    private static List<BakedQuad> bakeQuads(ConduitBundleRenderState bundleState, @Nullable BlockState state, @Nullable Direction side,
            RandomSource rand, ModelData extraData, @Nullable RenderType renderType, @Nullable Map<Holder<Conduit<?, ?>>, ResourceLocation> textures) {
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            var face = bundleState.layout().faces().get(direction);
            boolean isEnd = face.endpoint();
            Direction preRotation = rotateDirection(direction, side);
            IQuadTransformer rotation = ROTATIONS[direction.ordinal()];

            if (isEnd) {
                quads.addAll(rotation.process(
                        modelOf(CONDUIT_CONNECTOR).getQuads(state, preRotation, rand, extraData, renderType)));
            }

            for (var part : face.segments()) {
                var conduit = part.conduit();
                IQuadTransformer rotationTranslation = rotation.andThen(translation(part.offset()));
                var texture = textures == null ? bundleState.getTexture(conduit) : textures.get(conduit);
                quads.addAll(new ConduitTextureEmissiveQuadTransformer(sprite(texture), 0)
                        .andThen(rotationTranslation)
                        .process(modelOf(CONDUIT_CONNECTION).getQuads(state, preRotation, rand, extraData,
                                renderType)));

                ConduitModelModifier conduitModelModifier = textures == null ? ConduitModelModifiers.getModifier(conduit.value().type()) : null;
                if (conduitModelModifier != null) {
                    CompoundTag extraWorldData = bundleState.getExtraWorldData(conduit);
                    quads.addAll(rotationTranslation.process(conduitModelModifier.createConnectionQuads(conduit,
                            extraWorldData, side, direction, rand, renderType)));
                }

                if (isEnd) {
                    quads.addAll(rotationTranslation.process(modelOf(CONDUIT_CONNECTION_BOX).getQuads(state,
                            preRotation, rand, extraData, renderType)));

                    var connectionState = face.connections().get(conduit);
                    if (connectionState != null) {
                        IQuadTransformer color = rotationTranslation.andThen(new ColorQuadTransformer(
                                connectionState.inputChannel(), connectionState.outputChannel()));

                        BakedModel model = null;
                        if (connectionState.canInput() && connectionState.canOutput()) {
                            model = modelOf(CONDUIT_IO_IN_OUT);
                        } else if (connectionState.canInput()) {
                            model = modelOf(CONDUIT_IO_IN);
                        } else if (connectionState.canOutput()) {
                            model = modelOf(CONDUIT_IO_OUT);
                        }

                        if (model != null) {
                            quads.addAll(
                                    color.process(model.getQuads(state, preRotation, rand, extraData, renderType)));
                        }

                        // TODO: Need support for dual-color redstone control.
                        if (connectionState.isRedstoneSensitive()) {
                            quads.addAll(rotationTranslation
                                    .andThen(new ColorQuadTransformer(null, connectionState.redstoneChannel()))
                                    .process(modelOf(CONDUIT_IO_REDSTONE).getQuads(state, preRotation, rand,
                                            extraData, renderType)));
                        }
                    }
                }
            }
        }

        var layout = bundleState.layout();
        for (var core : layout.cores()) {
            var texture = textures == null ? bundleState.getTexture(core.conduit()) : textures.get(core.conduit());
            quads.addAll(new ConduitTextureEmissiveQuadTransformer(sprite(texture), 0)
                    .andThen(translation(core.offset())).process(modelOf(CONDUIT_CORE).getQuads(state, side, rand, extraData, renderType)));
        }
        if (layout.boxMin() != null) {
            quads.addAll(new BoxTextureQuadTransformer(layout.boxSize()).andThen(translation(layout.boxMin()))
                    .process(modelOf(BOX).getQuads(state, side, rand, extraData, renderType)));
        }
        return quads;
    }

    private static IQuadTransformer translation(Vec3i offset) {
        return TRANSLATIONS.asMap().computeIfAbsent(offset, position -> transformer(translateTransformation(position)));
    }

    private static IQuadTransformer transformer(Transformation transform) {
        // NeoForge initializes and mutates the normal matrix on first use. Finish it before sharing across chunk workers.
        transform.getNormalMatrix();
        return QuadTransformers.applying(transform);
    }

    /**
     * @param toDirection the Direction you want to transform to from the Bottom as base
     * @param toTransform the Direction to follow the same Transformation as bottom -> toDirection
     * @return the Direction toTransform was transformed to
     */
    @Nullable
    public static Direction rotateDirection(Direction toDirection, @Nullable Direction toTransform) {
        if (toTransform == null) {
            return null;
        }

        return switch (toDirection) {
        case DOWN -> toTransform;
        case UP -> toTransform.getClockWise(Direction.Axis.Z).getClockWise(Direction.Axis.Z);
        case NORTH -> toTransform.getCounterClockWise(Direction.Axis.X);
        case SOUTH -> toTransform.getClockWise(Direction.Axis.X);
        case WEST -> toTransform.getCounterClockWise(Direction.Axis.Z);
        case EAST -> toTransform.getClockWise(Direction.Axis.Z);
        };
    }

    public static Transformation rotateTransformation(Direction toDirection) {
        Quaternionf quaternion = new Quaternionf();
        switch (toDirection) {
        case UP -> quaternion.mul(Axis.ZP.rotationDegrees(180));
        case NORTH -> quaternion.mul(Axis.XP.rotationDegrees(90));
        case SOUTH -> quaternion.mul(Axis.XN.rotationDegrees(90));
        case WEST -> quaternion.mul(Axis.ZN.rotationDegrees(90));
        case EAST -> quaternion.mul(Axis.ZP.rotationDegrees(90));
        default -> {
        }
        }
        Transformation transformation = new Transformation(null, quaternion, null, null);
        return transformation.applyOrigin(new Vector3f(.5f, .5f, .5f));
    }

    private static Transformation translateTransformation(Vec3i offset) {
        return new Transformation(scale(offset, 3 / 16f), null, null, null);
    }

    private static Vector3f scale(Vec3i vector, float scaler) {
        return new Vector3f(vector.getX() * scaler, vector.getY() * scaler, vector.getZ() * scaler);
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
        return false;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return ModelHelper.getMissingTexture();
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        // This is only used for facades.
        ConduitBundleRenderState bundleState = data.get(ConduitBundleRenderState.PROPERTY);

        if (bundleState == null) {
            return ModelHelper.getMissingTexture();
        }

        if (bundleState.hasFacade() && ClientFacadeVisibility.areFacadesVisible()) {
            return Minecraft.getInstance()
                    .getBlockRenderer()
                    .getBlockModel(bundleState.facade())
                    .getParticleIcon(data.get(FACADE_MODEL_DATA));
        }

        // Shouldn't be called anymore, but sensible fallback to have:
        if (bundleState.conduits().isEmpty()) {
            return ModelHelper.getMissingTexture();
        }

        return sprite(bundleState.getTexture(bundleState.conduits().getFirst()));
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand,
            @NotNull ModelData data) {
        return CUTOUT_SET;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        ModelData data = IDynamicBakedModel.super.getModelData(level, pos, state, modelData);
        ConduitBundleRenderState bundleState = data.get(ConduitBundleRenderState.PROPERTY);
        if (bundleState == null || !bundleState.hasFacade()) {
            return data;
        }
        BlockState blockState = bundleState.facade();
        BakedModel blockModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(blockState);
        ModelData facadeData = blockModel.getModelData(level, pos, blockState, ModelData.EMPTY);
        return data.derive().with(FACADE_MODEL_DATA, facadeData).build();
    }

    private static TextureAtlasSprite sprite(ResourceLocation location) {
        return Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(location);
    }

    private static boolean isMissingModel(BakedModel model) {
        return model == Minecraft.getInstance().getModelManager().getMissingModel();
    }
}
