package com.enderio.enderio.client.content.paint;

import com.enderio.enderio.content.paint.block.PaintedBlock;
import com.enderio.enderio.content.paint.block.PaintedSlabBlock;
import com.enderio.enderio.content.paint.block.entity.PaintedBlockEntity;
import com.enderio.enderio.init.EIODataComponents;
import com.enderio.enderio.mixin.ItemColorsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class PaintedBlockColor implements BlockColor, ItemColor {

    public static PaintedBlockColor INSTANCE = new PaintedBlockColor();

    // TODO: Buggy on the sides of blocks. (Grass)
    @Override
    public int getColor(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        if (level != null && pos != null) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof PaintedBlockEntity paintedBlockEntity) {

                Optional<Block> paint = paintedBlockEntity.getPrimaryPaint();
                if (state.getBlock() instanceof PaintedSlabBlock && tintIndex >= 0) {
                    paint = paintedBlockEntity.getSecondaryPaint();
                }

                tintIndex = unmoveTintIndex(tintIndex);

                BlockState paintState = paint.orElse(PaintedBlock.DEFAULT_PAINT).defaultBlockState();
                int color = Minecraft.getInstance().getBlockColors().getColor(paintState, level, pos, tintIndex);
                if (color != -1) {
                    return color;
                }
            }
        }

        return 0xFFFFFF;
    }

    @Override
    public int getColor(ItemStack itemStack, int tintIndex) {
        var paintData = itemStack.get(EIODataComponents.BLOCK_PAINT);
        if (paintData != null) {
            return defaultItemTint(paintData.paint(), tintIndex);
        }

        return 0;
    }

    public static int defaultItemTint(Block paint, int tintIndex) {
        var item = paint.asItem();
        var colors = Minecraft.getInstance().getItemColors();
        // A plain BlockItem's default stack contains that item. Only registered providers need a stack.
        if (item.getClass() == BlockItem.class && colors.getClass() == ItemColors.class
            && !((ItemColorsAccessor) colors).enderio$itemColors().containsKey(item)) {
            return -1;
        }
        return colors.getColor(item.getDefaultInstance(), tintIndex);
    }

    //Lets assume that no one uses negative tint indices, so that we can move bottomslab tint indices into the negative half
    public static int moveTintIndex(int original) {
        return -original - 2;
    }

    public static int unmoveTintIndex(int original) {
        if (original > 0) {
            return original;
        } else {
            return -original - 2;
        }
    }
}
