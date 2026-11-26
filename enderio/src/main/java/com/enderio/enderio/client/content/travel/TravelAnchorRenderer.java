package com.enderio.enderio.client.content.travel;

import com.enderio.enderio.api.travel.TravelRenderer;
import com.enderio.enderio.client.EnderIOClient;
import com.enderio.enderio.client.foundation.renderer.OutlineBuffer;
import com.enderio.enderio.config.base.BaseConfig;
import com.enderio.enderio.content.travel.travel_anchor.AnchorTravelTarget;
import com.enderio.enderio.content.travel.travel_anchor.PaintedTravelAnchorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class TravelAnchorRenderer implements TravelRenderer<AnchorTravelTarget> {
    private static final Direction[] DIRECTIONS = Direction.values();
    private final RandomSource random = new SingleThreadedRandomSource(42L);
    private BakedModel backdropModel;
    private List<BakedQuad> backdropQuads = List.of();
    private String activeColorSetting, backgroundColorSetting;
    private int activeColor, backgroundColor;

    private List<BakedQuad> backdrop(Minecraft minecraft) {
        var model = minecraft.getModelManager().getModel(EnderIOClient.TRAVEL_ANCHOR_BACKDROP_MODEL_LOCATION);
        if (model != backdropModel) {
            var quads = new ArrayList<BakedQuad>();
            random.setSeed(42L);
            for (Direction direction : DIRECTIONS) {
                quads.addAll(model.getQuads(null, direction, random));
            }
            backdropQuads = List.copyOf(quads);
            backdropModel = model;
        }
        return backdropQuads;
    }

    private int outlineColor(boolean active) {
        if (active) {
            String setting = BaseConfig.CLIENT.ANCHORS_ACTIVE_COLOR.get();
            if (!setting.equals(activeColorSetting)) {
                activeColor = FastColor.ARGB32.color(255, Integer.parseInt(setting, 16));
                activeColorSetting = setting;
            }
            return activeColor;
        }
        String setting = BaseConfig.CLIENT.ANCHORS_BACKGROUND_COLOR.get();
        if (!setting.equals(backgroundColorSetting)) {
            backgroundColor = FastColor.ARGB32.color(255, Integer.parseInt(setting, 16));
            backgroundColorSetting = setting;
        }
        return backgroundColor;
    }

    private static double scale(Minecraft minecraft, double distance, boolean active) {
        return Math.max(1, (float) Math.sqrt(distance) * (active ? 1.3 : 1)
            * Math.sin(Math.toRadians(minecraft.options.fov().get() / 4d)));
    }

    @Override
    public boolean isVisible(AnchorTravelTarget target, Camera camera, Frustum frustum, boolean active) {
        if (!target.isVisible()) {
            return false;
        }
        var blockBounds = new AABB(target.pos());
        if (frustum.isVisible(blockBounds)) {
            return true;
        }
        // Item renderers and painted models may draw arbitrary geometry beyond a block.
        Minecraft minecraft = Minecraft.getInstance();
        if (target.icon() != Items.AIR || minecraft.level.getBlockEntity(target.pos()) instanceof PaintedTravelAnchorBlockEntity) {
            return true;
        }
        double extent = 5;
        String name = target.name().trim();
        if (!name.isEmpty()) {
            extent = Math.max(extent, minecraft.font.width(name) * 0.05 + minecraft.font.lineHeight * 0.2 + 2);
        }
        double distance = camera.getPosition().distanceTo(target.pos().getCenter());
        return frustum.isVisible(blockBounds.inflate(extent * scale(minecraft, distance, active)));
    }

    @Override
    public void render(AnchorTravelTarget travelData, LevelRenderer levelRenderer, PoseStack poseStack,
            double distanceSquared, boolean active, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (Objects.isNull(minecraft.level) || !travelData.isVisible()) {
            return;
        }

        poseStack.pushPose();

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vec3 offsetToCamera = travelData.pos().getCenter().vectorTo(cameraPos);
        Vec3 towardsCamera = offsetToCamera.normalize();
        double distanceToCamera = offsetToCamera.length();

        {
            double anchorScale = scale(minecraft, distanceToCamera, active);

            poseStack.translate(0.5, 0.5, 0.5);
            float s = (float) anchorScale;
            poseStack.scale(s, s, s);
            poseStack.translate(-0.5, -0.5, -0.5);
        }

        OutlineBuffer buffer = OutlineBuffer.INSTANCE;
        int outlineColor = outlineColor(active);
        int textColor = active ? outlineColor : 0xFFFFFFFF;
        float outlineR = ((outlineColor >> 16) & 0xFF) / 255F;
        float outlineG = ((outlineColor >> 8) & 0xFF) / 255F;
        float outlineB = (outlineColor & 0xFF) / 255F;

        VertexConsumer renderBuffer = buffer.getBuffer(RenderType.tripwire());

        int packedLight = LightTexture.pack(15, 15);

        Block iconBlock = Block.byItem(travelData.icon());

        BlockState blockState;
        boolean iconIsFullBlock = false;

        if (iconBlock != Blocks.AIR && BaseConfig.CLIENT.ANCHORS_USE_ICON_AS_BLOCK.get()) {
            blockState = iconBlock.defaultBlockState();
            iconIsFullBlock = Block.isShapeFullBlock(blockState.getOcclusionShape(minecraft.level, travelData.pos()));
        } else {
            blockState = minecraft.level.getBlockState(travelData.pos());

            if (minecraft.level.getBlockEntity(travelData.pos()) instanceof PaintedTravelAnchorBlockEntity paintedTravelAnchorBlock) {
                Optional<Block> paint = paintedTravelAnchorBlock.getPrimaryPaint();

                if (paint.isPresent()) {
                    blockState = paint.get().defaultBlockState();
                }
            }
        }

        boolean hasIcon = !(travelData.icon() == Items.AIR || iconIsFullBlock);

        if (!hasIcon) {
            // Render Model
            {
                BakedModel blockModel = minecraft.getBlockRenderer().getBlockModel(blockState);
                BlockColors blockColors = minecraft.getBlockColors();

                for (int face = -1; face < DIRECTIONS.length; face++) {
                    random.setSeed(42L);
                    Direction direction = face < 0 ? null : DIRECTIONS[face];
                    for (BakedQuad quad : blockModel.getQuads(blockState, direction, random)) {
                        int tint = quad.getTintIndex() == -1 ? 0xFFFFFF
                            : blockColors.getColor(blockState, minecraft.level, travelData.pos(), quad.getTintIndex());
                        renderBuffer.putBulkData(poseStack.last(), quad, ((tint >> 16) & 0xFF) / 255F,
                            ((tint >> 8) & 0xFF) / 255F, (tint & 0xFF) / 255F, 1, packedLight, OverlayTexture.NO_OVERLAY);
                    }
                }
            }

            // Render outline block
            {
                poseStack.pushPose();

                float outlineSize = active ? 0.2F : 0.15F;
                float scale = 1F + 2 * outlineSize;
                Vec3 offset = towardsCamera.scale(-scale).subtract(outlineSize, outlineSize, outlineSize);

                poseStack.translate(offset.x, offset.y, offset.z);
                poseStack.scale(scale, scale, scale);

                for (BakedQuad quad : backdrop(minecraft)) {
                    renderBuffer.putBulkData(poseStack.last(), quad, outlineR, outlineG, outlineB, 1, packedLight, OverlayTexture.NO_OVERLAY);
                }

                poseStack.popPose();
            }
        } else {
            // Render Icon
            poseStack.pushPose();

            {
                Vector3f upDir = new Vec3(0, 1, 0)
                    .xRot(-camera.getXRot() * ((float) Math.PI / 180F))
                    .yRot(-camera.getYRot() * ((float) Math.PI / 180F))
                    .toVector3f();
                Vector3f direction = towardsCamera.toVector3f();
                Quaternionf iconRotation = new Quaternionf().lookAlong(direction.x(), direction.y(), direction.z(), upDir.x(), upDir.y(), upDir.z());

                Vec3 offset = towardsCamera.scale(0.9);

                poseStack.translate(offset.x() + 0.5, offset.y() + 0.5, offset.z() + 0.5);
                poseStack.mulPose(iconRotation.invert());
                float s = active ? 1.3F : 1.0F;
                poseStack.scale(-s, s, -s);
            }

            // Draw the backdrop before the item: rendering the item switches the shared buffer, which ends the batch
            // renderBuffer writes to.
            {
                poseStack.pushPose();

                float s = 1.5F;
                poseStack.scale(s, s, s);
                poseStack.translate(-0.5, -0.5, -2);
                poseStack.rotateAround(Axis.ZN.rotationDegrees(45), 0.5F, 0.5F, 0.5F);

                for (BakedQuad quad : backdrop(minecraft)) {
                    renderBuffer.putBulkData(poseStack.last(), quad, outlineR, outlineG, outlineB, 1, packedLight, OverlayTexture.NO_OVERLAY);
                }

                poseStack.popPose();
            }

            ItemStack stack = travelData.icon().getDefaultInstance();
            BakedModel bakedmodel = minecraft.getItemRenderer().getModel(stack, minecraft.level, null, 0);
            minecraft
                .getItemRenderer()
                .render(stack, ItemDisplayContext.GUI, false, poseStack, OutlineBuffer.INSTANCE, packedLight, OverlayTexture.NO_OVERLAY, bakedmodel);

            poseStack.popPose();
        }

        if (!travelData.name().trim().isEmpty()) {
            // Render Text
            poseStack.pushPose();

            {
                Quaternionf textRotation = Axis.YN.rotationDegrees(camera.getYRot()).mul(Axis.XP.rotationDegrees(camera.getXRot()));
                int lineHeight = minecraft.font.lineHeight;
                double scale = 0.1;
                double textOffsetY = scale * lineHeight + 1.25;
                if (hasIcon && active) {
                    textOffsetY += 0.15;
                }

                poseStack.translate(0.5, 0.5, 0.5);
                poseStack.mulPose(textRotation);
                poseStack.translate(0, textOffsetY, 0);
                float s = (float) scale;
                poseStack.scale(-s, -s, s);
            }

            Component textComponent = Component.literal(travelData.name().trim());

            float textOpacitySetting = minecraft.options.getBackgroundOpacity(0.5f);
            int textBg = (int) (textOpacitySetting * 255) << 24;
            float halfWidth = (float) (-minecraft.font.width(textComponent) / 2);

            Matrix4f matrix4f = poseStack.last().pose();

            minecraft.font.drawInBatch(textComponent, halfWidth, 0, textColor, false, matrix4f, buffer, Font.DisplayMode.SEE_THROUGH, textBg, packedLight);
            minecraft.font.drawInBatch(textComponent, halfWidth, 0, textColor, false, matrix4f, buffer, Font.DisplayMode.NORMAL, 0, packedLight);

            poseStack.popPose();
        }

        poseStack.popPose();
    }
}
