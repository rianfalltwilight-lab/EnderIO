package com.enderio.enderio.tests.foundation.task;

import com.enderio.core.common.blockentity.EnderBlockEntity;
import com.enderio.enderio.content.machines.sag_mill.SagMillingRecipe;
import com.enderio.enderio.foundation.task.CraftingMachineTask;
import com.enderio.enderio.foundation.task.host.CraftingMachineTaskHost;
import com.enderio.enderio.init.EIORecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class CraftingMachineTaskHostTests {
    private static class Host extends CraftingMachineTaskHost<SagMillingRecipe, SagMillingRecipe.Input> {
        int searches;
        boolean forceRestart;
        RecipeHolder<SagMillingRecipe> available;

        Host(EnderBlockEntity block, AtomicBoolean powered) {
            super(block, powered::get, EIORecipes.SAG_MILLING.type().get(), (level, input, recipe) -> {
                @SuppressWarnings("unchecked")
                CraftingMachineTask<SagMillingRecipe, SagMillingRecipe.Input> task = mock(CraftingMachineTask.class);
                when(task.getRecipe()).thenReturn(recipe.value());
                return task;
            }, () -> mock(SagMillingRecipe.Input.class));
        }

        @Override
        protected Optional<RecipeHolder<SagMillingRecipe>> findRecipe() {
            searches++;
            return Optional.ofNullable(available);
        }

        @Override
        protected boolean shouldStartNewTask() {
            // Weather obelisks can request a restart without first consulting the recipe.
            return forceRestart || super.shouldStartNewTask();
        }
    }

    @Test
    void inputChangesAndPowerRestorationUseOneFreshLookupEach() {
        var block = mock(EnderBlockEntity.class);
        var level = mock(Level.class);
        when(block.getLevel()).thenReturn(level);
        var powered = new AtomicBoolean(false);
        var host = new Host(block, powered);
        verify(block).addDataSlot(any());
        var recipe = new RecipeHolder<>(ResourceLocation.withDefaultNamespace("first"), mock(SagMillingRecipe.class));
        host.available = recipe;
        host.newTaskAvailable();
        host.tick();
        assertEquals(0, host.searches);

        powered.set(true);
        host.tick();
        var firstTask = host.getCurrentTask();
        assertNotNull(firstTask);
        assertSame(recipe.value(), firstTask.getRecipe());
        assertEquals(1, host.searches);

        host.newTaskAvailable();
        host.tick();
        host.tick();
        assertSame(firstTask, host.getCurrentTask());
        assertEquals(2, host.searches);

        host.available = new RecipeHolder<>(ResourceLocation.withDefaultNamespace("second"), mock(SagMillingRecipe.class));
        host.newTaskAvailable();
        host.tick();
        assertSame(host.available.value(), host.getCurrentTask().getRecipe());
        assertEquals(3, host.searches);

        host.available = null;
        host.newTaskAvailable();
        host.tick();
        host.tick();
        assertNull(host.getCurrentTask());
        assertEquals(4, host.searches);

        host.available = recipe;
        host.newTaskAvailable();
        host.tick();
        var beforeRestart = host.getCurrentTask();
        host.forceRestart = true;
        host.newTaskAvailable();
        host.tick();
        assertNotSame(beforeRestart, host.getCurrentTask());
        assertSame(recipe.value(), host.getCurrentTask().getRecipe());
        assertEquals(6, host.searches);
    }
}
