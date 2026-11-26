package com.enderio.enderio.client.content.conduits;

import com.enderio.enderio.client.content.conduits.model.facades.ClientFacadeVisibility;
import com.enderio.enderio.compat.ModCompatHelper;
import com.enderio.enderio.content.conduits.bundle.ConduitBundleBlockEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;

@EventBusSubscriber(value = Dist.CLIENT)
public class ConduitFacadeRendering {

    private static final ThreadLocal<RandomSource> RANDOM = ThreadLocal
            .withInitial(() -> new SingleThreadedRandomSource(42L));

    @SubscribeEvent
    static void renderFacade(AddSectionGeometryEvent event) {
        var dimension = event.getLevel().dimension();

        var facadesForDimension = ConduitBundleBlockEntity.CHUNK_FACADES.get(dimension);
        if (facadesForDimension == null) {
            return;
        }

        LongSet blockList = facadesForDimension.getOrDefault(SectionPos.asLong(event.getSectionOrigin()), null);
        if (blockList == null) {
            return;
        }

        var facadesForDim = ConduitBundleBlockEntity.FACADES.get(dimension);
        if (facadesForDim == null) {
            return;
        }

        var facades = new Long2ObjectOpenHashMap<BlockState>(blockList.size());
        for (long entry : blockList) {
            BlockState state = facadesForDim.get(entry);
            if (state != null) {
                facades.put(entry, state);
            }
        }

        if (facades.isEmpty())
            return;

        event.addRenderer(new FacadeRenderer(facades, ClientFacadeVisibility.areFacadesVisible()));
    }

    private static class FacadeRenderer implements AddSectionGeometryEvent.AdditionalSectionRenderer {
        private final Long2ObjectOpenHashMap<BlockState> facades;
        private final boolean opaque;

        public FacadeRenderer(Long2ObjectOpenHashMap<BlockState> facades, boolean opaque) {
            this.facades = facades;
            this.opaque = opaque;
        }

        @Override
        public void render(AddSectionGeometryEvent.SectionRenderingContext context) {
            // Render nothing if a shader pack is in use - transparent facades do not render well with shaders.
            // See GH-1062 for more details.
            if (!this.opaque && ModCompatHelper.hasIris()) {
                if (IrisApi.getInstance().isShaderPackInUse()) {
                    return;
                }
            }

            VertexConsumerWrapper wrapper = opaque ? null : new AlphaWrapper(context);

            RandomSource random = RANDOM.get();
            var minecraft = Minecraft.getInstance();
            var models = minecraft.getModelManager().getBlockModelShaper();
            var renderer = minecraft.getBlockRenderer().getModelRenderer();
            var pose = context.getPoseStack();
            var region = context.getRegion();
            var cursor = new BlockPos.MutableBlockPos();
            for (var iterator = facades.long2ObjectEntrySet().fastIterator(); iterator.hasNext();) {
                var entry = iterator.next();
                var state = entry.getValue();
                var model = models.getBlockModel(state);
                cursor.set(entry.getLongKey());
                // Custom models can retain the position in their ModelData.
                BlockPos pos = model.getClass() == SimpleBakedModel.class ? cursor : cursor.immutable();
                pose.pushPose();
                pose.translate(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);

                random.setSeed(42L);
                var modelData = model.getModelData(region, pos, state, region.getModelData(pos));
                for (var renderType : model.getRenderTypes(state, random, modelData)) {
                    VertexConsumer consumer = wrapper == null ? context.getOrCreateChunkBuffer(renderType) : wrapper;
                    renderer.tesselateBlock(region, model, state, pos, pose, consumer, true, random, 42L,
                            OverlayTexture.NO_OVERLAY, modelData, renderType);
                }

                pose.popPose();
            }
        }

        private static class AlphaWrapper extends VertexConsumerWrapper {
            public AlphaWrapper(AddSectionGeometryEvent.SectionRenderingContext context) {
                super(context.getOrCreateChunkBuffer(RenderType.translucent()));
            }

            @Override
            public VertexConsumer setColor(int r, int g, int b, int a) {
                super.setColor(r, g, b, 85);
                return this;
            }
        }
    }
}
