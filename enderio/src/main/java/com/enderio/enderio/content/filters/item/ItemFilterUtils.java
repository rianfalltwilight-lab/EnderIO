package com.enderio.enderio.content.filters.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

public class ItemFilterUtils {
    public static boolean doComponentsMatch(ItemStack referenceStack, ItemStack stack) {
        var reference = referenceStack.getComponents();
        var candidate = stack.getComponents();
        boolean referenceHasDamage = reference.has(DataComponents.DAMAGE);
        boolean candidateHasDamage = candidate.has(DataComponents.DAMAGE);
        if (!referenceHasDamage && !candidateHasDamage && ItemStack.isSameItemSameComponents(referenceStack, stack)) {
            return true;
        }

        // Ignore damage, which is checked separately. Equal sizes rule out extra components.
        if (reference.size() - (referenceHasDamage ? 1 : 0) != candidate.size() - (candidateHasDamage ? 1 : 0)) {
            return false;
        }
        for (var component : reference) {
            if (component.type() != DataComponents.DAMAGE && !Objects.equals(candidate.get(component.type()), component.value())) {
                return false;
            }
        }

        return true;
    }
}
