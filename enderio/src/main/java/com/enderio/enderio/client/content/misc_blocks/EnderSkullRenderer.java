package com.enderio.enderio.client.content.misc_blocks;

import com.enderio.enderio.foundation.block.entity.EnderSkullBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.jetbrains.annotations.Nullable;

@EventBusSubscriber(value = Dist.CLIENT)
public class EnderSkullRenderer implements BlockEntityRenderer<EnderSkullBlockEntity> {

    public static final RenderType RENDERTYPE = RenderType.entityCutoutNoCullZOffset(ResourceLocation.withDefaultNamespace("textures/entity/enderman/enderman.png"));
    public static final ModelLayerLocation ENDER_SKULL = new ModelLayerLocation(ResourceLocation.withDefaultNamespace("enderman_head"), "main");

    private final EnderSkullModel skullmodelbase;
    @Nullable private static HitResult frameHit;

    @SubscribeEvent
    public static void beginFrame(RenderFrameEvent.Pre event) {
        frameHit = null;
    }

    public EnderSkullRenderer(BlockEntityRendererProvider.Context context) {
        skullmodelbase = new EnderSkullModel(context.getModelSet().bakeLayer(ENDER_SKULL));
    }

    @Override
    public void render(EnderSkullBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        float f = blockEntity.getAnimation(partialTick);
        BlockState blockstate = blockEntity.getBlockState();
        boolean flag = blockstate.getBlock() instanceof WallSkullBlock;
        Direction direction = flag ? blockstate.getValue(WallSkullBlock.FACING) : null;
        int i = flag ? RotationSegment.convertToSegment(direction.getOpposite()) : blockstate.getValue(SkullBlock.ROTATION);
        float f1 = RotationSegment.convertToDegrees(flag ? i : blockEntity.getRenderRotation(i));
        LocalPlayer player = Minecraft.getInstance().player;
        Vec3 position = player.position();
        if (frameHit == null) {
            frameHit = player.pick(10D, 0.0f, false);
        }
        skullmodelbase.active = false;
        if (frameHit instanceof BlockHitResult blockHitResult && blockHitResult.getBlockPos().equals(blockEntity.getBlockPos())
            && player.level().getBlockEntity(blockHitResult.getBlockPos()) == blockEntity) {
            blockEntity.setAnimation(30.0f);
            f = 30.0f;
        }
        if (f > 0) {
            skullmodelbase.active = true;
            f1 = (float) (Mth.atan2(position.z - blockEntity.getBlockPos().getZ() - 0.5D, position.x - blockEntity.getBlockPos().getX() - 0.5D) * 180.0f / Math.PI + 90);
            f1 += (float) (player.getRandom().nextGaussian() * 2);
            if (!flag) {
                blockEntity.setRenderRotation(RotationSegment.convertToSegment(f1));
            }
        }
        SkullBlockRenderer.renderSkull(direction, f1, f, poseStack, buffer, packedLight, skullmodelbase, RENDERTYPE);
    }

    public static class EnderSkullModel extends SkullModelBase {
        private final ModelPart head;
        private final ModelPart hat;
        private final ModelPart root;
        private boolean active = false;

        public EnderSkullModel(ModelPart root) {
            this.root = root;
            head = root.getChild("head");
            hat = root.getChild("hat");
        }

        public static MeshDefinition createHeadModel() {
            MeshDefinition meshdefinition = new MeshDefinition();
            PartDefinition partdefinition = meshdefinition.getRoot();
            partdefinition.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(0, 16).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(-0.5F)), PartPose.ZERO);
            partdefinition.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F), PartPose.ZERO);
            return meshdefinition;
        }

        public static LayerDefinition createMobHeadLayer() {
            MeshDefinition meshdefinition = createHeadModel();
            return LayerDefinition.create(meshdefinition, 64, 32);
        }

        @Override
        public void setupAnim(float p_103811_, float p_103812_, float p_103813_) {
            this.head.yRot = p_103812_ * ((float)Math.PI / 180F);
            this.head.xRot = p_103813_ * ((float)Math.PI / 180F);
            this.hat.yRot = head.yRot;
            this.hat.xRot = head.xRot;
            this.head.y = 0;
            if (active) {
                this.head.y =- 5f;
            }
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int packedColor) {
            this.root.render(poseStack, buffer, packedLight, packedOverlay, packedColor);
        }
    }
}
