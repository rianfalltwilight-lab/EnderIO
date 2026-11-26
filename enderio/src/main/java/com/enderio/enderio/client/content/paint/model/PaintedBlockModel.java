package com.enderio.enderio.client.content.paint.model;

import com.enderio.core.data.model.ModelHelper;
import com.enderio.enderio.client.content.paint.PaintedBlockColor;
import com.enderio.enderio.content.paint.block.entity.DoublePaintedBlockEntity;
import com.enderio.enderio.content.paint.block.entity.PaintedBlockEntity;
import com.enderio.enderio.content.paint.block.entity.SinglePaintedBlockEntity;
import com.enderio.enderio.init.EIODataComponents;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class PaintedBlockModel implements IDynamicBakedModel {

    private final Map<Block, List<BakedModel>> itemRenderCache = new ConcurrentHashMap<>();
    // The geometry creates a fresh model on every resource bake. Chunk workers can use it concurrently.
    private final Cache<PaintKey, List<BakedQuad>> paintedQuads = CacheBuilder.newBuilder().maximumSize(256).build();
    private final ItemStack referenceStack;

    private record PaintKey(BakedModel shape, BakedModel paintModel, BlockState paintState,
            @Nullable Direction side, @Nullable RenderType renderType, boolean lowerSlab) {}

    private static final IQuadTransformer LOWER_SLAB_TINT =
            quad -> quad.tintIndex = PaintedBlockColor.moveTintIndex(quad.getTintIndex());

    /**
     * The block which model we retexture
     */
    private final Block reference;

    /**
     * Rotate the item model perspective.
     * Used to fix stairs.
     */
    @Nullable
    private final Direction rotateItemTo;

    public PaintedBlockModel(Block reference, @Nullable Direction rotateItemTo) {
        this.reference = reference;
        this.referenceStack = reference.asItem().getDefaultInstance();
        this.rotateItemTo = rotateItemTo;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData extraData, @Nullable RenderType renderType) {
        if (state != null) {
            BlockState replicaState = replicateState(state);

            if (state.hasProperty(SlabBlock.TYPE)) {
                // Support slabs with different textures on top and bottom
                List<BakedQuad> quads = new ArrayList<>();
                SlabType slabType = state.getValue(SlabBlock.TYPE);

                // Bottom slab
                if (slabType == SlabType.BOTTOM || slabType == SlabType.DOUBLE) {
                    Block paint = extraData.get(DoublePaintedBlockEntity.PAINT);
                    BakedModel model = getModel(replicaState.setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    var shape = model.getQuads(state, side, rand, ModelData.EMPTY, renderType);
                    quads.addAll(paintShape(model, shape, paint, side, rand, null, renderType, true));
                }

                // Top slab
                if (slabType == SlabType.TOP || slabType == SlabType.DOUBLE) {
                    Block paint = extraData.get(DoublePaintedBlockEntity.PAINT2);
                    BakedModel model = getModel(replicaState.setValue(SlabBlock.TYPE, SlabType.TOP));
                    var shape = model.getQuads(state, side, rand, ModelData.EMPTY, renderType);
                    quads.addAll(paintShape(model, shape, paint, side, rand, null, renderType, false));
                }

                return quads;
            } else {
                // Simple model
                BakedModel model = getModel(replicaState);
                List<BakedQuad> shape = model.getQuads(replicaState, side, rand);
                Direction direction = null;
                for (Property<?> property : state.getProperties()) {
                    if (property instanceof DirectionProperty directionProperty) {
                        direction = state.getValue(directionProperty).getOpposite();
                    }
                }

                return paintShape(model, shape, extraData.get(SinglePaintedBlockEntity.PAINT), side, rand, direction,
                        renderType, false);
            }
        }

        return List.of();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
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
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        Block paint = data.get(SinglePaintedBlockEntity.PAINT);
        if (paint != null) {
            BakedModel model = getModel(paint.defaultBlockState());
            TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
            if (!sprite.contents().name().getPath().equals("missingno")) {
                return sprite;
            }
        }

        if (data.has(DoublePaintedBlockEntity.PAINT2)) {
            paint = data.get(DoublePaintedBlockEntity.PAINT2);
            if (paint != null) {
                BakedModel model = getModel(paint.defaultBlockState());
                return model.getParticleIcon(ModelData.EMPTY);
            }
        }

        return ModelHelper.getMissingTexture();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return ModelHelper.getMissingTexture();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState paintedBlockState, RandomSource rand, ModelData data) {
        ChunkRenderTypeSet chunkRenderTypeSet = ChunkRenderTypeSet
                .union(PaintedBlockEntity.PAINT_DATA_PROPERTIES.stream()
                        .map(data::get)
                        // remove all unset paints
                        .filter(Objects::nonNull)
                        .map(Block::defaultBlockState)
                        // do original ChunkRenderType lookup
                        .map(state -> getModel(state).getRenderTypes(state, rand, ModelData.EMPTY))
                        .toList());

        if (chunkRenderTypeSet.isEmpty()) {
            return ChunkRenderTypeSet.of(RenderType.solid());
        }
        return chunkRenderTypeSet;
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack itemStack, boolean fabulous) {
        if (!itemStack.has(EIODataComponents.BLOCK_PAINT)) {
            return List.of(fabulous ? Sheets.translucentCullBlockSheet() : Sheets.translucentItemSheet());
        }

        var paintData = itemStack.get(EIODataComponents.BLOCK_PAINT);
        return List.of(ItemBlockRenderTypes.getRenderType(paintData.paint().defaultBlockState(), fabulous));
    }

    @Override
    public List<BakedModel> getRenderPasses(ItemStack itemStack, boolean fabulous) {
        if (!itemStack.has(EIODataComponents.BLOCK_PAINT)) {
            return List.of();
        }

        var paintData = itemStack.get(EIODataComponents.BLOCK_PAINT);
        return itemRenderCache.computeIfAbsent(paintData.paint(),
                paintKey -> List.of(new ItemModel(paintData.paint())));
    }

    @Override
    public ItemTransforms getTransforms() {
        return getItemModel().getTransforms();
    }

    // region Model Shadowing Logic

    /**
     * Get the block model for a given block state.
     */
    private BakedModel getModel(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }

    /**
     * Get the reference block's item model.
     */
    private BakedModel getItemModel() {
        return Minecraft.getInstance()
                .getItemRenderer()
                .getModel(referenceStack, null, null, 0);
    }

    /**
     * Replicate the painted block state as the reference block.
     */
    private BlockState replicateState(@Nullable BlockState selfState) {
        BlockState toState = reference.defaultBlockState();
        if (selfState == null) {
            return toState;
        }

        for (Property<?> property : selfState.getProperties()) {
            if (property instanceof BooleanProperty booleanProperty && toState.hasProperty(booleanProperty)) {
                toState = toState.setValue(booleanProperty, selfState.getValue(booleanProperty));
            }

            // noinspection rawtypes,unchecked
            if (property instanceof EnumProperty enumProperty && toState.hasProperty(enumProperty)) {
                // noinspection unchecked
                toState = toState.setValue(enumProperty, selfState.getValue(enumProperty));
            }
        }

        return toState;
    }

    private List<BakedQuad> paintShape(BakedModel shapeModel, List<BakedQuad> shape, @Nullable Block paint,
            @Nullable Direction side, RandomSource rand, @Nullable Direction rotation, @Nullable RenderType renderType,
            boolean lowerSlab) {
        if (getClass() != PaintedBlockModel.class || shapeModel.getClass() != SimpleBakedModel.class || paint == null) {
            var quads = getQuadsUsingShape(paint, shape, side, rand, rotation, renderType);
            return lowerSlab ? LOWER_SLAB_TINT.process(quads) : quads;
        }
        BlockState paintState = paintWithRotation(paint, rotation);
        BakedModel paintModel = getModel(paintState);
        PaintKey key = null;
        if (paintModel.getClass() == SimpleBakedModel.class) {
            key = new PaintKey(shapeModel, paintModel, paintState, side, renderType, lowerSlab);
            var cached = paintedQuads.getIfPresent(key);
            if (cached != null) {
                return cached;
            }
        }
        var quads = recolor(shape, paint, paintState, paintModel, side, rand, rotation, renderType);
        if (lowerSlab) {
            quads = LOWER_SLAB_TINT.process(quads);
        }
        if (key != null) {
            quads = List.copyOf(quads);
            paintedQuads.put(key, quads);
        }
        return quads;
    }

    /**
     * @param paint
     * @param shape    quads you want to copy
     * @param side     side to render
     * @param rand
     * @param rotation rotation you want to have applied to the block
     * @return a List of BakedQuads from a shape using the paint as a texture
     */
    protected List<BakedQuad> getQuadsUsingShape(@Nullable Block paint, List<BakedQuad> shape, @Nullable Direction side,
            RandomSource rand, @Nullable Direction rotation, @Nullable RenderType renderType) {
        if (paint != null) {
            BlockState state = paintWithRotation(paint, rotation);
            return recolor(shape, paint, state, getModel(state), side, rand, rotation, renderType);
        }
        return List.of();
    }

    private List<BakedQuad> recolor(List<BakedQuad> shape, Block paint, BlockState state, BakedModel model,
            @Nullable Direction side, RandomSource rand, @Nullable Direction rotation, @Nullable RenderType renderType) {
        List<BakedQuad> face = model.getQuads(state, side, rand, ModelData.EMPTY, renderType);
        var result = new ArrayList<BakedQuad>(shape.size());
        for (BakedQuad quad : shape) {
            if (face.isEmpty()) {
                var fallback = getSpriteFromModel(quad, model, paint, rotation);
                result.add(paintQuad(quad, fallback.getFirst(), fallback.getSecond()));
            } else {
                BakedQuad texture = face.getFirst();
                result.add(paintQuad(quad, texture.getSprite(), texture.isTinted()));
            }
        }
        return result;
    }

    private BlockState paintWithRotation(Block paint, @Nullable Direction rotation) {
        BlockState state = paint.defaultBlockState();
        if (rotation != null) {
            for (Property<?> property : state.getProperties()) {
                if (property instanceof DirectionProperty directionProperty
                        && directionProperty.getPossibleValues().contains(rotation)) {
                    state = state.setValue(directionProperty, rotation);
                }
            }
        }
        return state;
    }

    /** Use the shape quad's own direction when the requested side has no paint quad. */
    protected Pair<TextureAtlasSprite, Boolean> getSpriteFromModel(BakedQuad shape, BakedModel model, Block paint,
            Direction rotation) {
        BlockState state = paintWithRotation(paint, rotation);
        List<BakedQuad> quads = model.getQuads(state, shape.getDirection(), PaintingQuadTransformer.randomFor(model));
        return quads.isEmpty() ? Pair.of(ModelHelper.getMissingTexture(), false)
                : Pair.of(quads.get(0).getSprite(), quads.get(0).isTinted());
    }

    /** Retexture a copy so the source baked model keeps its original vertices and tint. */
    protected BakedQuad paintQuad(BakedQuad toCopy, TextureAtlasSprite sprite, boolean shouldTint) {
        BakedQuad copied = new BakedQuad(toCopy.getVertices().clone(), shouldTint ? 1 : -1,
                toCopy.getDirection(), sprite, toCopy.isShade());

        PaintingQuadTransformer.remapUV(copied.getVertices(), toCopy.getSprite(), sprite);
        return copied;
    }

    // endregion

    /**
     * Simple pass-through item model.
     */
    private class ItemModel implements IDynamicBakedModel {

        private final Block paint;

        private ItemModel(Block paint) {
            this.paint = paint;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                ModelData extraData, @Nullable RenderType renderType) {
            BakedModel model = getItemModel();
            var shape = model.getQuads(state, side, rand, ModelData.EMPTY, renderType);
            return paintShape(model, shape, paint, side, rand, rotateItemTo, renderType, false);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return false;
        }

        @Override
        public boolean isGui3d() {
            return true;
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
            return PaintedBlockModel.this.getParticleIcon();
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }

        @Override
        public ItemTransforms getTransforms() {
            return getItemModel().getTransforms();
        }
    }
}
