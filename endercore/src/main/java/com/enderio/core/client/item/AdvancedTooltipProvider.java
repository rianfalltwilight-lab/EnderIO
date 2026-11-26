package com.enderio.core.client.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public interface AdvancedTooltipProvider {

    /**
     * Add tooltips that are always displayed
     * @param itemStack shown for this item
     * @param player the player
     * @param tooltips add tooltips to this list
     */
    default void addCommonTooltips(ItemStack itemStack, @Nullable Player player, List<Component> tooltips) {
    }

    /**
     * Tooltips shown when shift is hot held
     * @param itemStack shown for this item
     * @param player the player
     * @param tooltips add tooltips to this list
     */
    default void addBasicTooltips(ItemStack itemStack, @Nullable Player player, List<Component> tooltips) {
    }

    /**
     * Add tooltips that are displayed when shift is held
     * @param itemStack shown for this item
     * @param player the player
     * @param tooltips add tooltips to this list
     */
    default void addDetailedTooltips(ItemStack itemStack, @Nullable Player player, List<Component> tooltips) {
    }

    /** Whether to display the Shift hint. Existing providers can supply conditional lines based on the player. */
    default boolean hasDetailedTooltip(ItemStack itemStack, @Nullable Player player) {
        if (!TooltipOverrides.DETAILED.get(getClass())) {
            return false;
        }
        var details = new ArrayList<Component>();
        addDetailedTooltips(itemStack, player, details);
        return !details.isEmpty();
    }
}

class TooltipOverrides {
    static final ClassValue<Boolean> DETAILED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("addDetailedTooltips", ItemStack.class, Player.class, List.class)
                        .getDeclaringClass() != AdvancedTooltipProvider.class;
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
    };
}
