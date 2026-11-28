package com.enderio.enderio.compat.jade;

import com.enderio.enderio.content.conduits.bundle.ConduitBundleBlockEntity;
import com.enderio.enderio.content.conduits.probe.ConduitDiagnostics;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public enum ConduitComponentProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final String KEY = "enderio_conduits";

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        var lines = accessor.getServerData().getList(KEY, Tag.TAG_STRING);
        for (int i = 0; i < lines.size(); i++) {
            var line = Component.Serializer.fromJson(lines.getString(i), accessor.getLevel().registryAccess());
            if (line != null) {
                tooltip.add(line);
            }
        }
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof ConduitBundleBlockEntity bundle)) {
            return;
        }

        // Looking at a connector also reports that connection.
        var hit = bundle.getShape().getConnectionFromHit(accessor.getPosition(), accessor.getHitResult());
        Direction side = hit == null ? null : hit.getFirst();

        var lines = new ListTag();
        long gameTime = accessor.getLevel().getGameTime();
        for (var conduit : bundle.getConduits()) {
            var report = ConduitDiagnostics.describe(conduit, bundle.getConduitNode(conduit), side, gameTime);
            // The first line names the conduit, which Jade's title already does for a lone one.
            for (var line : bundle.getConduits().size() == 1 ? report.subList(1, report.size()) : report) {
                lines.add(StringTag.valueOf(Component.Serializer.toJson(line, accessor.getLevel().registryAccess())));
            }
        }
        data.put(KEY, lines);
    }

    @Override
    public ResourceLocation getUid() {
        return EIOJadePlugin.CONDUIT_COMPONENT;
    }
}
