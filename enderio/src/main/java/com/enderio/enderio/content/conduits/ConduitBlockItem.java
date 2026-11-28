package com.enderio.enderio.content.conduits;

import com.enderio.enderio.init.EIODataComponents;
import java.util.ArrayList;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.enderio.core.common.item.ICustomCreativeTabEntries;
import com.enderio.core.common.util.TooltipUtil;
import com.enderio.enderio.api.EnderIOCapabilities;
import com.enderio.enderio.api.EnderIODataComponents;
import com.enderio.enderio.api.EnderIORegistries;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.ConduitType;
import com.enderio.enderio.content.conduits.bundle.ConduitBundleBlockEntity;
import com.enderio.enderio.foundation.lang.EIOCommonLang;
import com.enderio.enderio.init.EIOBlocks;
import com.enderio.enderio.init.EIOItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

public class ConduitBlockItem extends BlockItem implements ICustomCreativeTabEntries {

    public ConduitBlockItem(Properties properties) {
        super(EIOBlocks.CONDUIT_BUNDLE.get(), properties);
    }

    public static ItemStack getStackFor(Holder<Conduit<?, ?>> conduit, int count) {
        var stack = new ItemStack(EIOItems.CONDUIT.get(), count);
        stack.set(EnderIODataComponents.CONDUIT, conduit);
        return stack;
    }

    @Override
    public Component getName(ItemStack stack) {
        Holder<Conduit<?, ?>> conduit = stack.get(EnderIODataComponents.CONDUIT);
        if (conduit == null) {
            return super.getName(stack);
        }

        return conduit.value().description();
    }

    // Do not use block description, as that is Conduit Bundle.
    // This will ensure missing conduits use the missing conduit string.
    @Override
    public String getDescriptionId() {
        return getOrCreateDescriptionId();
    }

    public static boolean isLineMode(ItemStack stack) {
        return stack.getItem() instanceof ConduitBlockItem && stack.has(EIODataComponents.CONDUIT_LINE_MODE);
    }

    /** Sneak and use on air to switch line mode on or off. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }

        if (!level.isClientSide()) {
            boolean enable = !stack.has(EIODataComponents.CONDUIT_LINE_MODE);
            if (enable) {
                stack.set(EIODataComponents.CONDUIT_LINE_MODE, true);
            } else {
                stack.remove(EIODataComponents.CONDUIT_LINE_MODE);
            }
            stack.remove(EIODataComponents.CONDUIT_LINE_START);
            player.displayClientMessage(enable ? ConduitLang.CONDUIT_LINE_ON : ConduitLang.CONDUIT_LINE_OFF, true);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !isLineMode(context.getItemInHand())) {
            return super.useOn(context);
        }

        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        var clicked = context.getClickedPos();
        var clickedState = level.getBlockState(clicked);
        var target = clickedState.canBeReplaced() || clickedState.is(EIOBlocks.CONDUIT_BUNDLE.get()) ? clicked
                : clicked.relative(context.getClickedFace());

        var stack = context.getItemInHand();
        var start = stack.get(EIODataComponents.CONDUIT_LINE_START);
        if (start == null || !start.dimension().equals(level.dimension())) {
            stack.set(EIODataComponents.CONDUIT_LINE_START, GlobalPos.of(level.dimension(), target));
            player.displayClientMessage(ConduitLang.CONDUIT_LINE_STARTED, true);
        } else {
            stack.remove(EIODataComponents.CONDUIT_LINE_START);
            placeLine(context, player, start.pos(), target);
        }

        return InteractionResult.CONSUME;
    }

    /**
     * Places the whole line or nothing. This runs inside the use of the item, so placement events and protection mods
     * see every block of it.
     */
    private void placeLine(UseOnContext context, Player player, BlockPos start, BlockPos end) {
        Level level = context.getLevel();
        var stack = context.getItemInHand();
        Holder<Conduit<?, ?>> conduit = stack.get(EnderIODataComponents.CONDUIT);
        if (conduit == null) {
            return;
        }

        if (start.distManhattan(end) + 1 > ConduitLine.MAX_LENGTH) {
            player.displayClientMessage(TooltipUtil.withArgs(ConduitLang.CONDUIT_LINE_TOO_LONG, ConduitLine.MAX_LENGTH), true);
            return;
        }

        var needed = new ArrayList<BlockPos>();
        for (var pos : ConduitLine.route(start, end)) {
            var bundle = level.getBlockEntity(pos) instanceof ConduitBundleBlockEntity found ? found : null;
            if (bundle != null && bundle.hasConduitStrict(conduit)) {
                continue;
            }

            boolean usable = level.isLoaded(pos) && level.mayInteract(player, pos)
                    && player.mayUseItemAt(pos, context.getClickedFace(), stack)
                    && (bundle != null ? bundle.canAddConduit(conduit) : level.getBlockState(pos).canBeReplaced());
            if (!usable) {
                player.displayClientMessage(
                        TooltipUtil.withArgs(ConduitLang.CONDUIT_LINE_BLOCKED, pos.getX(), pos.getY(), pos.getZ()), true);
                return;
            }
            needed.add(pos);
        }

        if (!player.getAbilities().instabuild && stack.getCount() < needed.size()) {
            player.displayClientMessage(TooltipUtil.withArgs(ConduitLang.CONDUIT_LINE_NOT_ENOUGH, needed.size()), true);
            return;
        }

        // Placing consumes the stack, which may empty it before the last block.
        var template = stack.copy();
        int placed = 0;
        for (var pos : needed) {
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), context.getClickedFace(), pos, false);
            var placeStack = player.getAbilities().instabuild ? template.copy() : stack;
            if (place(new BlockPlaceContext(level, player, context.getHand(), placeStack, hit)).consumesAction()) {
                placed++;
            }
        }

        player.displayClientMessage(TooltipUtil.withArgs(ConduitLang.CONDUIT_LINE_PLACED, placed), true);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level level = context.getLevel();
        @Nullable
        Player player = context.getPlayer();
        BlockPos blockpos = context.getClickedPos();

        // Allow placing from the edge of an adjacent block
        BlockState blockState = level.getBlockState(blockpos);
        if (!blockState.canBeReplaced()) {
            // noinspection DataFlowIssue
            return blockState
                    .useItemOn(context.getItemInHand(), level, player, context.getHand(),
                            context.getHitResult().withPosition(blockpos))
                    .result();
        }

        return super.place(context);
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        // Ensure the originalStack being used is valid for placement.
        var stack = context.getItemInHand();
        if (!stack.has(EnderIODataComponents.CONDUIT)) {
            var facadeProvider = stack.getCapability(EnderIOCapabilities.CONDUIT_FACADE_PROVIDER);
            if (facadeProvider == null || !facadeProvider.isValid()) {
                return false;
            }
        }

        boolean result = super.placeBlock(context, state);

        // Save the clicked face for connection logic later.
        if (result) {
            var level = context.getLevel();
            if (level.getBlockEntity(context.getClickedPos()) instanceof ConduitBundleBlockEntity conduitBundle) {
                // Try to work out what the player wants to click.
                // If they click directly onto the conduit they want to extend, prioritise that
                // face.
                // Otherwise, try to determine based on their horizontal direction
                var clickedFace = context.getClickedFace();
                var horizontalDirection = context.getHorizontalDirection();

                if (level.getBlockState(context.getClickedPos().relative(clickedFace.getOpposite()))
                        .is(EIOBlocks.CONDUIT_BUNDLE.get())) {
                    conduitBundle.primaryConnectionSide = clickedFace.getOpposite();
                } else if (level.getBlockState(context.getClickedPos().relative(horizontalDirection.getOpposite()))
                        .is(EIOBlocks.CONDUIT_BUNDLE.get())) {
                    conduitBundle.primaryConnectionSide = horizontalDirection.getOpposite();
                }
            }
        }

        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        Holder<Conduit<?, ?>> conduit = stack.get(EnderIODataComponents.CONDUIT);
        if (conduit != null) {
            conduit.value().addToTooltip(context, tooltipComponents::add, tooltipFlag);

            boolean showDetailTooltip = !tooltipFlag.hasShiftDown()
                    && (conduit.value().hasAdvancedTooltip() || conduit.value().showDebugTooltip());

            if (conduit.value().showDebugTooltip() && tooltipFlag.hasShiftDown()) {
                if (conduit.value().type().ticker() != null) {
                    tooltipComponents.add(TooltipUtil.styledWithArgs(ConduitLang.GRAPH_TICK_RATE_TOOLTIP,
                        20 / conduit.value().type().getTickRate(conduit)));
                }
            }

            if (showDetailTooltip) {
                tooltipComponents.add(EIOCommonLang.SHOW_DETAIL_TOOLTIP);
            }
        }

        tooltipComponents.add(stack.has(EIODataComponents.CONDUIT_LINE_MODE) ? ConduitLang.CONDUIT_LINE_ON
                : ConduitLang.CONDUIT_LINE_HINT);

        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public boolean shouldAddDefaultItem() {
        return false;
    }

    @Override
    public void addAdditionalCreativeTabEntries(CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
        var registry = parameters.holders().lookupOrThrow(EnderIORegistries.Keys.CONDUIT);
        var conduitTypes = registry.listElements().toList();

        var conduitClassTypes = conduitTypes.stream()
            .map(e -> e.value().getClass())
            .sorted(Comparator.comparing(Class::getName))
            .distinct()
            .toList();

        for (var conduitClass : conduitClassTypes) {
            var matchingConduitTypes = conduitTypes.stream()
                .filter(e -> e.value().getClass() == conduitClass)
                // GRIM...
                .sorted((o1, o2) -> compareConduitTo(o1.value(), o2.value()))
                .toList();

            for (var conduitType : matchingConduitTypes) {
                output.accept(ConduitBlockItem.getStackFor(conduitType, 1), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            }
        }
    }

    private static <T extends Conduit<T, ?>> int compareConduitTo(Conduit<T, ?> o1, Conduit<?, ?> o2) {
        return o1.compareTo((T) o2);
    }
}
