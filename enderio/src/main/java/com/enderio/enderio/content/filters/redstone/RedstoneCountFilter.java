package com.enderio.enderio.content.filters.redstone;

import com.enderio.enderio.api.filter.RedstoneOutputFilter;
import com.enderio.enderio.api.filter.RedstoneOutputFilterContext;
import com.enderio.enderio.foundation.network.packets.ServerboundCountFilterPacket;
import com.enderio.enderio.init.EIODataComponents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

public class RedstoneCountFilter implements RedstoneOutputFilter {

    public static final Component INSTANCE = new Component(DyeColor.GREEN, 8, 0, false);
    private final ItemStack stack;

    public RedstoneCountFilter(ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public int getOutputSignal(RedstoneOutputFilterContext context, DyeColor control) {
        var previous = stack.get(EIODataComponents.REDSTONE_COUNT_FILTER);
        boolean powered = context.isActive(previous.channel1());
        int count = previous.count();
        if (powered && previous.deactivated()) {
            count++;
        }
        if (count > previous.maxCount()) {
            count = 1;
        }
        boolean deactivated = !powered;
        if (count != previous.count() || previous.deactivated() != deactivated) {
            stack.set(EIODataComponents.REDSTONE_COUNT_FILTER,
                    new Component(previous.channel1(), previous.maxCount(), count, deactivated));
        }
        return count == previous.maxCount() ? 15 : 0;
    }

    public DyeColor getChannel() {
        return stack.get(EIODataComponents.REDSTONE_COUNT_FILTER).channel1();
    }

    public int getMaxCount() {
        return stack.get(EIODataComponents.REDSTONE_COUNT_FILTER).maxCount();
    }

    public void setMaxCount(int maxCount) {
        var component = stack.get(EIODataComponents.REDSTONE_COUNT_FILTER);
        stack.set(EIODataComponents.REDSTONE_COUNT_FILTER,
                new Component(component.channel1, maxCount, component.count, component.deactivated));
    }

    public int getCount() {
        return stack.get(EIODataComponents.REDSTONE_COUNT_FILTER).count();
    }

    public void setCount(int count) {
        var component = stack.get(EIODataComponents.REDSTONE_COUNT_FILTER);
        stack.set(EIODataComponents.REDSTONE_COUNT_FILTER,
                new Component(component.channel1, component.maxCount, count, component.deactivated));
    }

    public boolean isDeactivated() {
        return stack.get(EIODataComponents.REDSTONE_COUNT_FILTER).deactivated();
    }

    public void setDeactivated(boolean lastActive) {
        var component = stack.get(EIODataComponents.REDSTONE_COUNT_FILTER);
        stack.set(EIODataComponents.REDSTONE_COUNT_FILTER,
                new Component(component.channel1, component.maxCount, component.count, lastActive));
    }

    public void setState(ServerboundCountFilterPacket packet) {
        stack.set(EIODataComponents.REDSTONE_COUNT_FILTER,
                new Component(packet.channel1(), packet.maxCount(), packet.count(), packet.active()));
    }

    public void setChannel(DyeColor channel) {
        var component = stack.get(EIODataComponents.REDSTONE_COUNT_FILTER);
        stack.set(EIODataComponents.REDSTONE_COUNT_FILTER,
                new Component(channel, component.maxCount, component.count, component.deactivated));
    }

    @Override
    public boolean isConfigured() {
        Component current = stack.get(EIODataComponents.REDSTONE_COUNT_FILTER);
        return current != null && !current.equals(INSTANCE);
    }

    public record Component(DyeColor channel1, int maxCount, int count, boolean deactivated) {
        public static final Codec<Component> CODEC = RecordCodecBuilder.create(instance -> instance
                .group(DyeColor.CODEC.fieldOf("channel1").forGetter(Component::channel1),
                        ExtraCodecs.NON_NEGATIVE_INT.fieldOf("maxCount").forGetter(Component::maxCount),
                        ExtraCodecs.NON_NEGATIVE_INT.fieldOf("ticks").forGetter(Component::count),
                        Codec.BOOL.fieldOf("deactivated").forGetter(Component::deactivated))
                .apply(instance, Component::new));

        public static final StreamCodec<ByteBuf, Component> STREAM_CODEC = StreamCodec.composite(DyeColor.STREAM_CODEC,
                Component::channel1, ByteBufCodecs.VAR_INT, Component::maxCount, ByteBufCodecs.VAR_INT,
                Component::count, ByteBufCodecs.BOOL, Component::deactivated, Component::new);
    }
}
