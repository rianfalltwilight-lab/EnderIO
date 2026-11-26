package com.enderio.enderio.client.content.machines.renderer.blockentity;

import com.enderio.core.client.RenderUtil;
import com.enderio.enderio.EnderIO;
import com.enderio.enderio.content.machines.capacitor_bank.DisplayMode;
import com.enderio.enderio.content.machines.capacitor_bank.CapacitorBankDisplay.Size;
import com.enderio.enderio.content.machines.capacitor_bank.CapacitorBankManager;
import com.enderio.enderio.content.machines.capacitor_bank.CapacitorBankBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.phys.AABB;


public class CapacitorBankBER implements BlockEntityRenderer<CapacitorBankBlockEntity> {

    private static final ResourceLocation FULL_BAR = EnderIO.rl("block/capacitor_additionals/capacitor_bank_bar_full");
    private static final ResourceLocation END_BAR = EnderIO.rl("block/capacitor_additionals/capacitor_bank_bar_end");
    private static final ResourceLocation ENERGY_BAR = EnderIO.rl("block/capacitor_additionals/capacitor_bank_bar_energy");

    private static final ResourceLocation IO_1X1 = EnderIO.rl("block/capacitor_additionals/1x1_full");
    private static final ResourceLocation IO_FULL = EnderIO.rl("block/capacitor_additionals/full");

    private static final ResourceLocation SMALL_R = EnderIO.rl("block/capacitor_additionals/small_r");
    private static final ResourceLocation SMALL_L = EnderIO.rl("block/capacitor_additionals/small_l");
    private static final ResourceLocation SMALL_LR = EnderIO.rl("block/capacitor_additionals/small_lr");
    private static final ResourceLocation SMALL_U = EnderIO.rl("block/capacitor_additionals/small_u");
    private static final ResourceLocation SMALL_D = EnderIO.rl("block/capacitor_additionals/small_d");
    private static final ResourceLocation SMALL_UD = EnderIO.rl("block/capacitor_additionals/small_ud");
    private static final ResourceLocation CORNER_TR = EnderIO.rl("block/capacitor_additionals/corner_tr");
    private static final ResourceLocation CORNER_TL = EnderIO.rl("block/capacitor_additionals/corner_tl");
    private static final ResourceLocation SIDE_T = EnderIO.rl("block/capacitor_additionals/side_t");
    private static final ResourceLocation CORNER_BR = EnderIO.rl("block/capacitor_additionals/corner_br");
    private static final ResourceLocation CORNER_BL = EnderIO.rl("block/capacitor_additionals/corner_bl");
    private static final ResourceLocation SIDE_B = EnderIO.rl("block/capacitor_additionals/side_b");
    private static final ResourceLocation SIDE_R = EnderIO.rl("block/capacitor_additionals/side_r");
    private static final ResourceLocation SIDE_L = EnderIO.rl("block/capacitor_additionals/side_l");
    private static final Map<ResourceLocation, TextureAtlasSprite> SPRITES = new HashMap<>();
    private static final Map<CapacitorBankBlockEntity, Bounds> BOUNDS = new WeakHashMap<>();
    private record Bounds(long revision, long added, long sent, AABB box) {}
    private record FontWidths(int scaleWidth, int io, int input, int output) {}
    private static FontWidths widths;

    public static void clearResources() {
        SPRITES.clear();
        BOUNDS.clear();
        widths = null;
    }

    private static FontWidths widths(Font font) {
        if (widths == null) {
            widths = new FontWidths(font.width("OutputOutput"), font.width("I/0"), font.width("Input"), font.width("Output"));
        }
        return widths;
    }

    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    public CapacitorBankBER(BlockEntityRendererProvider.Context context) {

    }

    @Override
    public void render(CapacitorBankBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
        int packedOverlay) {

        if (blockEntity.getLevel() != null) {
            for (Direction direction: HORIZONTAL_DIRECTIONS) {
                var face = blockEntity.clientDisplay().face(blockEntity, direction);
                if (face.barLength() > 0) {
                    renderBar(blockEntity, poseStack, bufferSource, direction, face.barLength());
                } else if (face.mode() == DisplayMode.IO) {
                    renderIO(blockEntity, poseStack, bufferSource, direction, face.size());
                }
            }
        }
    }

    private static void renderBar(CapacitorBankBlockEntity blockEntity, PoseStack poseStack, MultiBufferSource bufferSource, Direction facing, int length) {
        for (int i = 0; i < length; i++) {
            poseStack.pushPose();
            poseStack.translate(0, -i, 0);
            VertexConsumer buffer = bufferSource.getBuffer(RenderType.cutout());
            PoseStack.Pose last = poseStack.last();
            renderTopHalf(last, buffer, facing, i == 0);
            renderBottomHalf(last, buffer, facing, i == length - 1);
            poseStack.popPose();
        }
        CapacitorBankManager.CapacitorSyncData data = CapacitorBankManager.getData(blockEntity.getUuid());
        if (data != null && data.capacity() > 0) {
            float f = data.storedEnergy() / (float) data.capacity();
            int filledPixels = Math.round(f * (10 + (length-1)*16));
            for (int i = length - 1; i >= 0; i--) {
                if (filledPixels <= 0) {
                    break;
                }

                poseStack.pushPose();
                poseStack.translate(0, -i, 0);
                VertexConsumer buffer = bufferSource.getBuffer(RenderType.cutout());
                PoseStack.Pose last = poseStack.last();
                boolean isBottomEnd = i == length - 1;
                renderEnergy(last, buffer, facing, filledPixels, isBottomEnd);
                filledPixels -= isBottomEnd ? 13 : 16;
                poseStack.popPose();
            }
        }
    }
    private static void renderIO(CapacitorBankBlockEntity capacitorBank, PoseStack poseStack, MultiBufferSource bufferSource, Direction facing, Size size) {
        int light = LightTexture.FULL_BRIGHT;
        if (capacitorBank.getLevel() != null) {
            light = LevelRenderer.getLightColor(capacitorBank.getLevel(), capacitorBank.getBlockPos().relative(facing));
        }

        CapacitorBankManager.CapacitorSyncData data = CapacitorBankManager.getData(capacitorBank.getUuid());
        if (data == null) {
            return;
        }

        renderTexture(poseStack.last(), bufferSource.getBuffer(RenderType.cutout()), facing, texture(size), light);
        if (size.ownsText()) {
            int height = size.y1() - size.y0() + 1;
            int width = size.x1() - size.x0() + 1;
            boolean isLongInformation = height > 1.4f * width;
            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.mulPose(Axis.YN.rotationDegrees(facing.get2DDataValue()*90));
            poseStack.translate(width%2 == 0 ? 0.5f : 0, height%2 == 0 ? -0.5f : 0,0.502f);
            poseStack.scale(1/16f,-1/16f,1/16f);
            Font font = Minecraft.getInstance().font;
            float fontWidthScale = width / (float)widths(font).scaleWidth();
            float fontHeightScale = height / (float)(font.lineHeight * 5);
            float scale = Math.min(fontWidthScale, fontHeightScale) * 16;
            poseStack.scale(scale,scale,scale);
            if (!isLongInformation) {
                poseStack.pushPose();
                poseStack.translate(-widths(font).io() / 2f, -font.lineHeight * 1.5f, 0);
                font.drawInBatch("I/0", 0, 0, 0xFF000000, false, poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, light, false);
                poseStack.popPose();
                poseStack.pushPose();
                long energySurplus = data.added() - data.send();
                int color = 0;
                if (energySurplus > 0) {
                    color = 0xFF00FF00;
                }

                if (energySurplus < 0) {
                    color = 0xFFFF0000;
                }

                poseStack.translate(-font.width(String.valueOf(energySurplus)) / 2f, font.lineHeight * 0.5f, 0);
                font.drawInBatch(String.valueOf(energySurplus), 0, 0, color, false, poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, light, false);
                poseStack.popPose();
            } else {
                poseStack.pushPose();
                poseStack.translate(-widths(font).input() / 2f, -font.lineHeight * 3.5f, 0);
                font.drawInBatch("Input", 0, 0, 0xFF000000, false, poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, light, false);
                poseStack.popPose();
                poseStack.pushPose();
                poseStack.translate(-font.width(String.valueOf(data.added())) / 2f, -font.lineHeight * 1.5f, 0);
                font.drawInBatch(String.valueOf(data.added()), 0, 0, 0xFF00FF00, false, poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, light, false);
                poseStack.popPose();
                poseStack.pushPose();
                poseStack.translate(-widths(font).output() / 2f, font.lineHeight * 0.5f, 0);
                font.drawInBatch("Output", 0, 0, 0xFF000000, false, poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, light, false);
                poseStack.popPose();
                poseStack.pushPose();
                poseStack.translate(-font.width(String.valueOf(data.send())) / 2f, font.lineHeight * 2.5f, 0);
                font.drawInBatch(String.valueOf(data.send()), 0, 0, 0xFFFF0000, false, poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, light, false);
                poseStack.popPose();

            }
            poseStack.popPose();
        }
    }

    private static TextureAtlasSprite sprite(ResourceLocation location) {
        var sprite = SPRITES.get(location);
        if (sprite == null) {
            sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(location);
            SPRITES.put(location, sprite);
        }
        return sprite;
    }

    public static void renderBottomHalf(PoseStack.Pose pose, VertexConsumer consumer, Direction facing, boolean isEnd) {
        TextureAtlasSprite texture = sprite(isEnd ? END_BAR : FULL_BAR);
        float inset = -0.001F;
        RenderUtil.renderFace(facing, pose, consumer, texture, 0, 0, inset, 1, 0.5f, 0xFFFFFFFF);
    }

    public static void renderTopHalf(PoseStack.Pose pose, VertexConsumer consumer, Direction facing, boolean isEnd) {
        TextureAtlasSprite texture = sprite(isEnd ? END_BAR : FULL_BAR);
        float inset = -0.001F;
        RenderUtil.renderFace(facing, pose, consumer, texture, 0, 0.5f, inset, 1, 0.5f, 0xFFFFFFFF);
    }

    public static void renderEnergy(PoseStack.Pose pose, VertexConsumer consumer, Direction facing, int pixels, boolean isBottomEnd) {
        TextureAtlasSprite texture = sprite(ENERGY_BAR);
        float inset = -0.002F;
        RenderUtil.renderFace(facing, pose, consumer, texture, 0, isBottomEnd ? 3/16f : 0, inset, 1, Math.min(pixels, isBottomEnd ? 13 : 16)/16f, 0xFFFFFFFF);
    }

    public static void renderTexture(PoseStack.Pose pose, VertexConsumer consumer, Direction facing, ResourceLocation rl, int light) {
        TextureAtlasSprite texture = sprite(rl);
        float inset = -0.001F;
        RenderUtil.renderFace(facing, pose, consumer, texture, 0, 0, inset, 1, 1, 0xFFFFFFFF, light);
    }

    @Override
    public int getViewDistance() {
        return 92;
    }

    @Override
    public AABB getRenderBoundingBox(CapacitorBankBlockEntity blockEntity) {
        var pos = blockEntity.getBlockPos();
        if (blockEntity.getLevel() == null) {
            return new AABB(pos);
        }
        var display = blockEntity.clientDisplay();
        var data = CapacitorBankManager.getData(blockEntity.getUuid());
        long added = data == null ? -1 : data.added();
        long sent = data == null ? -1 : data.send();
        var previous = BOUNDS.get(blockEntity);
        if (previous != null && previous.revision() == display.revision() && previous.added() == added && previous.sent() == sent) {
            return previous.box();
        }
        AABB box = new AABB(pos);
        for (var facing : HORIZONTAL_DIRECTIONS) {
            var face = display.face(blockEntity, facing);
            if (face.mode() == DisplayMode.NONE) {
                continue;
            }
            if (face.barLength() > 0 || face.mode() == DisplayMode.IO) {
                box = box.minmax(new AABB(pos).expandTowards(facing.getStepX() * 0.002, -Math.max(0, face.barLength() - 1), facing.getStepZ() * 0.002));
            }
            if (data != null && face.mode() == DisplayMode.IO && face.size().ownsText()) {
                box = box.minmax(textBounds(pos, facing, face.size(), data));
            }
        }
        BOUNDS.put(blockEntity, new Bounds(display.revision(), added, sent, box));
        return box;
    }

    private static AABB textBounds(BlockPos pos, Direction facing, Size size, CapacitorBankManager.CapacitorSyncData data) {
        var font = Minecraft.getInstance().font;
        var widths = widths(font);
        int width = size.x1() - size.x0() + 1;
        int height = size.y1() - size.y0() + 1;
        boolean tall = height > 1.4f * width;
        int maxWidth = tall ? Math.max(Math.max(widths.input(), widths.output()),
            Math.max(font.width(Long.toString(data.added())), font.width(Long.toString(data.send()))))
            : Math.max(widths.io(), font.width(Long.toString(data.added() - data.send())));
        double scale = Math.min(width / (float) widths.scaleWidth(), height / (float) (font.lineHeight * 5));
        double halfWidth = (maxWidth / 2.0 + 1) * scale;
        double halfHeight = ((tall ? 3.5 : 1.5) * font.lineHeight + 1) * scale;
        var right = facing.getCounterClockWise();
        double offset = width % 2 == 0 ? 0.5 : 0;
        double x = pos.getX() + 0.5 + right.getStepX() * offset + facing.getStepX() * 0.502;
        double y = pos.getY() + (height % 2 == 0 ? 0 : 0.5);
        double z = pos.getZ() + 0.5 + right.getStepZ() * offset + facing.getStepZ() * 0.502;
        double dx = Math.abs(right.getStepX()) * halfWidth + 0.001;
        double dz = Math.abs(right.getStepZ()) * halfWidth + 0.001;
        return new AABB(x - dx, y - halfHeight, z - dz, x + dx, y + halfHeight, z + dz);
    }

    private static ResourceLocation texture(Size size) {
        boolean isLeft = size.x0() == 0;
        boolean isRight = size.x1() == 0;
        boolean isSmallX = isLeft && isRight;
        boolean isUp = size.y0() == 0;
        boolean isDown = size.y1() == 0;
        boolean isSmallY = isUp && isDown;

        if (isSmallX && isSmallY) {
            return IO_1X1;
        }

        if (isSmallY) {
            if (isLeft) {
                return SMALL_R;
            }

            if (isRight) {
                return SMALL_L;
            }

            return SMALL_LR;
        }

        if (isSmallX) {
            if (isUp) {
                return SMALL_U;
            }

            if (isDown) {
                return SMALL_D;
            }

            return SMALL_UD;
        }

        if (isUp) {
            if (isLeft) {
                return CORNER_TR;
            }

            if (isRight) {
                return CORNER_TL;
            }

            return SIDE_T;
        }

        if (isDown) {
            if (isLeft) {
                return CORNER_BR;
            }

            if (isRight) {
                return CORNER_BL;
            }

            return SIDE_B;
        }

        if (isLeft) {
            return SIDE_R;
        }

        if (isRight) {
            return SIDE_L;
        }

        return IO_FULL;
    }
}
