package com.enderio.enderio.content.machines.capacitor_bank;

import com.enderio.core.client.item.AdvancedTooltipProvider;
import com.enderio.core.common.energy.ItemStackEnergy;
import com.enderio.core.common.util.TooltipUtil;
import com.enderio.enderio.EnderIO;
import com.enderio.enderio.foundation.block.EIOEntityBlock;
import com.enderio.enderio.init.EIODataComponents;
import com.enderio.enderio.foundation.lang.EIOCommonLang;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

@EventBusSubscriber
public class CapacitorBankBlock extends EIOEntityBlock<CapacitorBankBlockEntity> implements AdvancedTooltipProvider {

    public static final ResourceLocation PLACE_ADVANCEMENT_ID = EnderIO.rl("place_capacitor_bank");

    private final CapacitorTier tier;

    public CapacitorBankBlock(Supplier<BlockEntityType<? extends CapacitorBankBlockEntity>> blockEntityTypeSupplier, Properties properties, CapacitorTier tier) {
        super(blockEntityTypeSupplier, properties);
        this.tier = tier;
    }

    public CapacitorTier getTier() {
        return tier;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        throw new NotImplementedException("Block codecs are a later problem...");
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
        ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer != null && level.getBlockEntity(pos) instanceof CapacitorBankBlockEntity capacitorBankBlock) {
            for (Direction direction : Direction.values()) {
                if (level.getBlockEntity(pos.relative(direction)) instanceof CapacitorBankBlockEntity other
                    && other.tier() == tier) {
                    return;
                }
            }
            capacitorBankBlock.setDisplayMode(placer.getDirection().getOpposite(), DisplayMode.BAR);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
        BlockHitResult hitResult) {

        if (level.getBlockEntity(pos) instanceof MenuProvider menuProvider) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(menuProvider, pos);
            }

            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        return super.useWithoutItem(state, level, pos, player, hitResult);
    }

    @Override
    protected @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        // Do not allow opening in spectator mode.
        // TODO: We can convert our menus to not use a BE backing fully to enable this.
        return null;
    }

    @Override
    public void addCommonTooltips(ItemStack itemStack, @Nullable Player player, List<Component> tooltips) {
        String energy = String.format("%,d", ItemStackEnergy.getEnergyStored(itemStack)) + "/"
            + String.format("%,d", ItemStackEnergy.getMaxEnergyStored(itemStack));
        tooltips.add(TooltipUtil.styledWithArgs(EIOCommonLang.ENERGY_AMOUNT, energy));
    }

    @SubscribeEvent
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide && event.getEntity().isShiftKeyDown() && event.getLevel()
            .getBlockEntity(event.getHitVec().getBlockPos()) instanceof CapacitorBankBlockEntity capacitorBank) {
            if (capacitorBank.onShiftRightClick(event.getHitVec().getDirection(), event.getEntity())) {
                event.setCanceled(true);
            }
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CapacitorBankBlockEntity capBank) {
            capBank.removeNode();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void chargeDroppedBank(BlockDropsEvent event) {
        if (!(event.getBlockEntity() instanceof CapacitorBankBlockEntity bank)) {
            return;
        }
        int preview = bank.previewDropEnergy();
        if (preview == 0) {
            return;
        }
        ItemEntity first = null;
        for (var drop : event.getDrops()) {
            if (drop.getItem().is(event.getState().getBlock().asItem()) && drop.getItem().getOrDefault(EIODataComponents.ENERGY, 0) == preview) {
                drop.getItem().remove(EIODataComponents.ENERGY);
                if (first == null) {
                    first = drop;
                }
            }
        }
        if (first != null) {
            int amount = bank.claimDroppedEnergy();
            if (amount > 0) {
                if (first.getItem().getCount() > 1) {
                    var charged = first.copy();
                    charged.setItem(first.getItem().split(1));
                    event.getDrops().add(charged);
                    first = charged;
                }
                first.getItem().set(EIODataComponents.ENERGY, amount);
            }
        }
    }
}
