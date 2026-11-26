package com.enderio.enderio.client.content.conduits.model.facades;

import com.enderio.core.data.model.ModelHelper;
import com.enderio.enderio.client.content.conduits.model.ConduitAdditionalModels;
import com.enderio.enderio.init.EIODataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
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
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class FacadeItemModel implements IDynamicBakedModel {

    private final Map<Block, List<BakedModel>> itemRenderCache = new ConcurrentHashMap<>();
    private final @Nullable Block facade;
    private final ItemStack facadeStack;
    private final BakedModel facadeModel;
    private final AtomicReferenceArray<FaceQuads> faces = new AtomicReferenceArray<>(7);

    private record FaceQuads(BakedModel paint, BakedModel overlay, List<BakedQuad> quads) {}

    public FacadeItemModel(BakedModel facadeModel) {
        this(facadeModel, null);
    }

    private FacadeItemModel(BakedModel facadeModel, @Nullable Block facade) {
        this.facade = facade;
        this.facadeStack = facade == null ? ItemStack.EMPTY : facade.asItem().getDefaultInstance();
        this.facadeModel = facadeModel;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData extraData, @Nullable RenderType renderType) {
        if (facade == null) {
            return facadeModel.getQuads(null, side, rand, extraData, renderType);
        }
        BakedModel paint = getItemModel();
        BakedModel overlay = ConduitAdditionalModels.modelOf(ConduitAdditionalModels.CONDUIT_FACADE_OVERLAY);
        int face = side == null ? 6 : side.ordinal();
        boolean cacheable = paint.getClass() == SimpleBakedModel.class && overlay.getClass() == SimpleBakedModel.class;
        if (cacheable) {
            FaceQuads cached = faces.get(face);
            if (cached != null && cached.paint == paint && cached.overlay == overlay) {
                return cached.quads;
            }
        }
        List<BakedQuad> bakedQuads = new ArrayList<>(paint.getQuads(facade.defaultBlockState(), side, rand, extraData, renderType));
        bakedQuads.addAll(overlay.getQuads(null, side, rand, extraData, renderType));
        if (cacheable) {
            bakedQuads = List.copyOf(bakedQuads);
            faces.set(face, new FaceQuads(paint, overlay, bakedQuads));
        }
        return bakedQuads;
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
        return ModelHelper.getMissingTexture();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public ItemTransforms getTransforms() {
        return ConduitAdditionalModels.modelOf(ConduitAdditionalModels.CONDUIT_FACADE_OVERLAY).getTransforms();
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack itemStack, boolean fabulous) {
        if (!itemStack.has(EIODataComponents.BLOCK_PAINT)) {
            return List.of(RenderType.solid());
        }

        var paintData = itemStack.get(EIODataComponents.BLOCK_PAINT);
        if (paintData == null) {
            return List.of(RenderType.cutout());
        }

        var pass = (FacadeItemModel) passesFor(paintData.paint()).getFirst();
        return pass.getItemModel().getRenderTypes(pass.facadeStack, fabulous);
    }

    @Override
    public List<BakedModel> getRenderPasses(ItemStack itemStack, boolean fabulous) {
        if (!itemStack.has(EIODataComponents.BLOCK_PAINT)) {
            return List.of(this);
        }

        var paintData = itemStack.get(EIODataComponents.BLOCK_PAINT);
        return passesFor(paintData.paint());
    }

    private List<BakedModel> passesFor(Block paint) {
        return itemRenderCache.computeIfAbsent(paint, key -> List.of(new FacadeItemModel(facadeModel, key)));
    }

    /**
     * Get the reference block's item model.
     */
    private BakedModel getItemModel() {
        return Minecraft.getInstance().getItemRenderer().getModel(facadeStack, null, null, 0);
    }

}
