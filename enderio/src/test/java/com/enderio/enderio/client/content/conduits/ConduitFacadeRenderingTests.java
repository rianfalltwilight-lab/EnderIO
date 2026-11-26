package com.enderio.enderio.client.content.conduits;

import com.enderio.enderio.content.conduits.bundle.ConduitBundleBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConduitFacadeRenderingTests {
    @Test
    void sectionRendererKeepsItsSnapshotAndStablePositionsForCustomModels() {
        var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("enderio:facade_test"));
        var origin = new BlockPos(-16, 64, -16);
        var one = origin.offset(1, 2, 3);
        var two = origin.offset(4, 5, 6);
        var state = Blocks.GLASS.defaultBlockState();
        var facades = new Long2ObjectOpenHashMap<BlockState>();
        facades.put(one.asLong(), state);
        facades.put(two.asLong(), state);
        var sections = new Long2ObjectOpenHashMap<LongSet>();
        sections.put(SectionPos.asLong(origin), new LongOpenHashSet(facades.keySet()));
        var level = mock(Level.class);
        when(level.dimension()).thenReturn(dimension);
        var event = new AddSectionGeometryEvent(origin, level);
        var client = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        when(client.isSameThread()).thenReturn(true);
        var model = mock(BakedModel.class);
        when(client.getModelManager().getBlockModelShaper().getBlockModel(state)).thenReturn(model);
        var positions = new ArrayList<BlockPos>();
        when(model.getModelData(any(), any(), eq(state), any())).thenAnswer(call -> {
            positions.add(call.getArgument(1));
            return ModelData.EMPTY;
        });
        when(model.getRenderTypes(eq(state), any(), any())).thenReturn(ChunkRenderTypeSet.none());
        var region = mock(BlockAndTintGetter.class);
        when(region.getModelData(any())).thenReturn(ModelData.EMPTY);
        var context = new AddSectionGeometryEvent.SectionRenderingContext(type -> { throw new AssertionError(type); }, region, new PoseStack());
        try (var mc = mockStatic(Minecraft.class)) {
            mc.when(Minecraft::getInstance).thenReturn(client);
            ConduitBundleBlockEntity.FACADES.put(dimension, facades);
            ConduitBundleBlockEntity.CHUNK_FACADES.put(dimension, sections);
            try {
                ConduitFacadeRendering.renderFacade(event);
            } finally {
                ConduitBundleBlockEntity.FACADES.remove(dimension);
                ConduitBundleBlockEntity.CHUNK_FACADES.remove(dimension);
            }
            facades.clear();
            sections.clear();
            event.getAdditionalRenderers().getFirst().render(context);
        }
        assertEquals(2, positions.size());
        assertEquals(Set.of(one, two), Set.copyOf(positions));
        assertTrue(positions.stream().noneMatch(BlockPos.MutableBlockPos.class::isInstance));
    }
}
