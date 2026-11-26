package com.enderio.enderio.client.content.conduits.model.bundle;

import com.enderio.core.annotations.UseOnly;
import com.enderio.core.common.util.Area;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.bundle.ConduitBundle;
import com.enderio.enderio.client.content.conduits.model.modifier.ConduitModelModifiers;
import com.enderio.enderio.content.conduits.OffsetHelper;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class ConduitBundleRenderState {
    public static final ModelProperty<ConduitBundleRenderState> PROPERTY = new ModelProperty<>();

    private Direction.Axis mainAxis;
    private List<Holder<Conduit<?, ?>>> conduits;
    private Map<Holder<Conduit<?, ?>>, CompoundTag> extraWorldData;
    private Map<Direction, List<Holder<Conduit<?, ?>>>> conduitsByDirection;
    private Layout layout;

    record Part(Holder<Conduit<?, ?>> conduit, Vec3i offset) {}
    record Face(List<Part> segments, Map<Holder<Conduit<?, ?>>, ConduitConnectionRenderState> connections) {
        boolean endpoint() {
            return !connections.isEmpty();
        }
    }
    record Layout(Map<Direction, Face> faces, List<Part> cores, @Nullable Vec3i boxMin, @Nullable Vec3i boxSize) {}

    private boolean hasFacade;
    private BlockState facadeBlockstate;
    private boolean doesFacadeHideConduits;

    @UseOnly(LogicalSide.CLIENT)
    public static ConduitBundleRenderState of(ConduitBundle bundle) {
        var renderState = new ConduitBundleRenderState();

        renderState.mainAxis = Direction.Axis.Z;
        renderState.conduits = List.copyOf(bundle.getConduits());

        renderState.extraWorldData = new HashMap<>();
        for (var conduit : renderState.conduits) {
            var tag = bundle.getConduitExtraWorldData(conduit);
            if (tag != null) {
                renderState.extraWorldData.put(conduit, tag.copy());
            }
        }

        renderState.conduitsByDirection = new EnumMap<>(Direction.class);
        for (var side : Direction.values()) {
            var connected = List.copyOf(bundle.getConnectedConduits(side));
            renderState.conduitsByDirection.put(side, connected);
            if (!connected.isEmpty()) {
                renderState.mainAxis = side.getAxis();
            }
        }

        renderState.layout = renderState.buildLayout(bundle);

        renderState.hasFacade = bundle.hasFacade();
        if (renderState.hasFacade) {
            renderState.facadeBlockstate = bundle.getFacadeBlock().defaultBlockState();
            renderState.doesFacadeHideConduits = bundle.getFacadeType().doesHideConduits();
        } else {
            renderState.facadeBlockstate = Blocks.AIR.defaultBlockState();
            renderState.doesFacadeHideConduits = false;
        }

        return renderState;
    }

    Layout layout() {
        return layout;
    }

    private Layout buildLayout(ConduitBundle bundle) {
        var faces = new EnumMap<Direction, Face>(Direction.class);
        Map<Holder<Conduit<?, ?>>, List<Vec3i>> offsets = new HashMap<>();
        for (var side : Direction.values()) {
            var connected = getConnectedConduits(side);
            var segments = new ArrayList<Part>(connected.size());
            for (int i = 0; i < connected.size(); i++) {
                var conduit = connected.get(i);
                var offset = OffsetHelper.translationFor(side.getAxis(), OffsetHelper.offsetConduit(i, connected.size()));
                segments.add(new Part(conduit, offset));
                offsets.computeIfAbsent(conduit, ignored -> new ArrayList<>()).add(offset);
            }
            Map<Holder<Conduit<?, ?>>, ConduitConnectionRenderState> connections = new HashMap<>();
            for (var conduit : conduits) {
                if (bundle.getConnectionStatus(conduit, side).isEndpoint()) {
                    connections.put(conduit, ConduitConnectionRenderState.of(bundle.getConnectionConfig(conduit, side)));
                }
            }
            faces.put(side, new Face(List.copyOf(segments), Map.copyOf(connections)));
        }

        Area box = null;
        var individual = new ArrayList<Part>();
        var occupied = new HashSet<Vec3i>();
        Map<Holder<Conduit<?, ?>>, Integer> disconnected = new HashMap<>();
        for (int i = 0; i < conduits.size(); i++) {
            var conduit = conduits.get(i);
            var positions = offsets.get(conduit);
            if (positions == null) {
                disconnected.put(conduit, i);
                continue;
            }
            var first = positions.getFirst();
            if (positions.stream().allMatch(first::equals)) {
                individual.add(new Part(conduit, first));
                if (occupied.add(first)) {
                    continue;
                }
            }
            if (box == null) {
                box = new Area(first);
            }
            positions.forEach(box::makeContain);
        }

        var cores = new ArrayList<Part>();
        for (var part : individual) {
            if (box == null || !box.contains(part.offset())) {
                cores.add(part);
            }
        }
        for (var entry : disconnected.entrySet()) {
            var offset = OffsetHelper.translationFor(mainAxis, OffsetHelper.offsetConduit(entry.getValue(), conduits.size()));
            if (box == null || !box.contains(offset)) {
                cores.add(new Part(entry.getKey(), offset));
            }
        }
        return new Layout(Map.copyOf(faces), List.copyOf(cores), box == null ? null : box.getMin(), box == null ? null : box.size());
    }

    public List<Holder<Conduit<?, ?>>> conduits() {
        return conduits;
    }

    @Nullable
    public CompoundTag getExtraWorldData(Holder<Conduit<?, ?>> conduit) {
        return extraWorldData.get(conduit);
    }

    public List<Holder<Conduit<?, ?>>> getConnectedConduits(Direction side) {
        return conduitsByDirection.getOrDefault(side, List.of());
    }

    public boolean isConnectionEndpoint(Direction side) {
        return layout.faces().get(side).endpoint();
    }

    public ConduitConnectionRenderState getConnectionState(Direction side, Holder<Conduit<?, ?>> conduit) {
        return layout.faces().get(side).connections().get(conduit);
    }

    public Direction.Axis mainAxis() {
        return mainAxis;
    }

    public ResourceLocation getTexture(Holder<Conduit<?, ?>> conduit) {
        var modifier = ConduitModelModifiers.getModifier(conduit.value().type());
        if (modifier != null) {
            return modifier.getTexture(conduit, getExtraWorldData(conduit));
        } else {
            return conduit.value().texture();
        }
    }

    public boolean hasFacade() {
        return hasFacade;
    }

    public BlockState facade() {
        return facadeBlockstate;
    }

    public boolean doesFacadeHideConduits() {
        return doesFacadeHideConduits;
    }

}
