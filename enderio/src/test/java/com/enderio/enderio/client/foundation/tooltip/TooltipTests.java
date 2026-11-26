package com.enderio.enderio.client.foundation.tooltip;

import com.enderio.core.client.item.AdvancedTooltipProvider;
import com.enderio.enderio.api.soul.Soul;
import com.enderio.enderio.foundation.lang.EIOCommonLang;
import com.enderio.enderio.init.EIODataComponents;
import com.enderio.enderio.init.EIOItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class TooltipTests {
    @Test
    void hintMatchesConditionalAddonDetailsAndHonorsCheapPredicates() {
        var lines = new ArrayList<Component>();
        var player = mock(Player.class);
        var apple = new ItemStack(Items.APPLE);
        var addon = new AdvancedTooltipProvider() {
            public void addDetailedTooltips(ItemStack stack, Player viewer, List<Component> output) {
                if (stack.is(Items.APPLE) && viewer == player) {
                    output.add(Component.literal("detail"));
                }
            }
        };
        TooltipHandler.addAdvancedTooltips(addon, apple, null, lines, false);
        assertTrue(lines.isEmpty());
        TooltipHandler.addAdvancedTooltips(addon, apple, player, lines, false);
        assertEquals(List.of(EIOCommonLang.SHOW_DETAIL_TOOLTIP), lines);
        lines.clear();
        TooltipHandler.addAdvancedTooltips(addon, apple, player, lines, true);
        assertEquals(List.of(Component.literal("detail")), lines);

        var fast = mock(AdvancedTooltipProvider.class);
        when(fast.hasDetailedTooltip(apple, player)).thenReturn(true);
        lines.clear();
        TooltipHandler.addAdvancedTooltips(fast, apple, player, lines, false);
        verify(fast, never()).addDetailedTooltips(any(), any(), any());
        assertEquals(List.of(EIOCommonLang.SHOW_DETAIL_TOOLTIP), lines);
    }

    @Test
    void soulVialOnlyAdvertisesHealthDetailsWithBothHealthValues() {
        var vial = EIOItems.SOUL_VIAL.toStack();
        var provider = EIOItems.SOUL_VIAL.get();
        assertFalse(provider.hasDetailedTooltip(vial, null));
        var nbt = new CompoundTag();
        nbt.putFloat("Health", 3);
        vial.set(EIODataComponents.SOUL, new Soul(EntityType.PIG, nbt));
        assertFalse(provider.hasDetailedTooltip(vial, null));
        vial.set(EIODataComponents.ENTITY_MAX_HEALTH, 10f);
        assertTrue(provider.hasDetailedTooltip(vial, null));
        var lines = new ArrayList<Component>();
        provider.addDetailedTooltips(vial, null, lines);
        assertEquals(1, lines.size());
    }
}
