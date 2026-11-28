package com.enderio.enderio.content.conduits.type.item;

import com.enderio.enderio.api.conduits.ConduitRedstoneSignalAware;
import com.enderio.enderio.api.conduits.connection.config.ConnectionConfig;
import com.enderio.enderio.api.conduits.connection.config.ConnectionConfigType;
import com.enderio.enderio.api.conduits.connection.config.IOConnectionConfig;
import com.enderio.enderio.api.conduits.connection.config.RedstoneSensitiveConnectionConfig;
import com.enderio.enderio.api.io.RedstoneControl;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;

import java.util.List;

public record ItemConduitConnectionConfig(boolean isInsert, DyeColor insertChannel, boolean isExtract,
        DyeColor extractChannel, RedstoneControl extractRedstoneControl, DyeColor extractRedstoneChannel,
        boolean isRoundRobin, boolean isSelfFeed, int priority, int extractInterval, boolean isBalanced)
        implements IOConnectionConfig, RedstoneSensitiveConnectionConfig {

    /** Longest extraction interval a connection can be set to, in ticks. */
    public static final int MAX_EXTRACT_INTERVAL = 1200;

    /** A configuration with the conduit's own extraction interval and unbalanced delivery. */
    public ItemConduitConnectionConfig(boolean isInsert, DyeColor insertChannel, boolean isExtract, DyeColor extractChannel,
            RedstoneControl extractRedstoneControl, DyeColor extractRedstoneChannel, boolean isRoundRobin, boolean isSelfFeed,
            int priority) {
        this(isInsert, insertChannel, isExtract, extractChannel, extractRedstoneControl, extractRedstoneChannel, isRoundRobin,
                isSelfFeed, priority, 0, false);
    }

    public static final ItemConduitConnectionConfig DEFAULT = new ItemConduitConnectionConfig(false, DyeColor.GREEN, true,
            DyeColor.GREEN, RedstoneControl.NEVER_ACTIVE, DyeColor.RED, false, false, 0);

    public static final MapCodec<ItemConduitConnectionConfig> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(Codec.BOOL.fieldOf("is_insert").forGetter(ItemConduitConnectionConfig::isInsert),
                    DyeColor.CODEC.fieldOf("insert_channel").forGetter(ItemConduitConnectionConfig::insertChannel),
                    Codec.BOOL.fieldOf("is_extract").forGetter(ItemConduitConnectionConfig::isExtract),
                    DyeColor.CODEC.fieldOf("extract_channel").forGetter(ItemConduitConnectionConfig::extractChannel),
                    RedstoneControl.CODEC.fieldOf("extract_redstone_control")
                            .forGetter(ItemConduitConnectionConfig::extractRedstoneControl),
                    DyeColor.CODEC.fieldOf("extract_redstone_channel")
                            .forGetter(ItemConduitConnectionConfig::extractRedstoneChannel),
                    Codec.BOOL.fieldOf("is_round_robin").forGetter(ItemConduitConnectionConfig::isRoundRobin),
                    Codec.BOOL.fieldOf("is_self_feed").forGetter(ItemConduitConnectionConfig::isSelfFeed),
                    Codec.INT.fieldOf("priority").forGetter(ItemConduitConnectionConfig::priority),
                    Codec.INT.optionalFieldOf("extract_interval", 0).forGetter(ItemConduitConnectionConfig::extractInterval),
                    Codec.BOOL.optionalFieldOf("is_balanced", false).forGetter(ItemConduitConnectionConfig::isBalanced))
            .apply(instance, ItemConduitConnectionConfig::new));

    // Too many fields for the composite helpers.
    public static final StreamCodec<ByteBuf, ItemConduitConnectionConfig> STREAM_CODEC = StreamCodec.of(
            (buffer, config) -> {
                ByteBufCodecs.BOOL.encode(buffer, config.isInsert);
                DyeColor.STREAM_CODEC.encode(buffer, config.insertChannel);
                ByteBufCodecs.BOOL.encode(buffer, config.isExtract);
                DyeColor.STREAM_CODEC.encode(buffer, config.extractChannel);
                RedstoneControl.STREAM_CODEC.encode(buffer, config.extractRedstoneControl);
                DyeColor.STREAM_CODEC.encode(buffer, config.extractRedstoneChannel);
                ByteBufCodecs.BOOL.encode(buffer, config.isRoundRobin);
                ByteBufCodecs.BOOL.encode(buffer, config.isSelfFeed);
                ByteBufCodecs.INT.encode(buffer, config.priority);
                ByteBufCodecs.INT.encode(buffer, config.extractInterval);
                ByteBufCodecs.BOOL.encode(buffer, config.isBalanced);
            },
            buffer -> new ItemConduitConnectionConfig(ByteBufCodecs.BOOL.decode(buffer), DyeColor.STREAM_CODEC.decode(buffer),
                    ByteBufCodecs.BOOL.decode(buffer), DyeColor.STREAM_CODEC.decode(buffer),
                    RedstoneControl.STREAM_CODEC.decode(buffer), DyeColor.STREAM_CODEC.decode(buffer),
                    ByteBufCodecs.BOOL.decode(buffer), ByteBufCodecs.BOOL.decode(buffer), ByteBufCodecs.INT.decode(buffer),
                    ByteBufCodecs.INT.decode(buffer), ByteBufCodecs.BOOL.decode(buffer)));

    public static final ConnectionConfigType<ItemConduitConnectionConfig> TYPE = new ConnectionConfigType<>(CODEC,
            STREAM_CODEC.cast(), () -> DEFAULT);

    @Override
    public ConnectionConfig reconnected() {
        return new ItemConduitConnectionConfig(false, insertChannel, true, extractChannel, extractRedstoneControl,
                extractRedstoneChannel, isRoundRobin, isSelfFeed, priority, extractInterval, isBalanced);
    }

    @Override
    public ConnectionConfig disconnected() {
        return new ItemConduitConnectionConfig(false, insertChannel, false, extractChannel, extractRedstoneControl,
                extractRedstoneChannel, isRoundRobin, isSelfFeed, priority, extractInterval, isBalanced);
    }

    @Override
    public boolean canInsert(ConduitRedstoneSignalAware signalAware) {
        // TODO: sendRedstoneControl
        return isInsert();
    }

    @Override
    public boolean canExtract(ConduitRedstoneSignalAware signalAware) {
        if (!isExtract()) {
            return false;
        }

        if (extractRedstoneControl.isRedstoneSensitive()) {
            return extractRedstoneControl.isActive(signalAware.hasRedstoneSignal(extractRedstoneChannel));
        } else {
            return extractRedstoneControl == RedstoneControl.ALWAYS_ACTIVE;
        }
    }

    @Override
    public List<DyeColor> getRedstoneSignalColors() {
        if (extractRedstoneControl.isRedstoneSensitive()) {
            return List.of(extractRedstoneChannel);
        }

        return List.of();
    }

    public ItemConduitConnectionConfig withIsInsert(boolean isInsert) {
        return new ItemConduitConnectionConfig(isInsert, this.insertChannel, this.isExtract, this.extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, this.priority, this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withInsertChannel(DyeColor insertChannel) {
        return new ItemConduitConnectionConfig(this.isInsert, insertChannel, this.isExtract, this.extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, this.priority, this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withIsExtract(boolean isExtract) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, isExtract, this.extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, this.priority, this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withExtractChannel(DyeColor extractChannel) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, this.isExtract, extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, this.priority, this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withExtractRedstoneControl(RedstoneControl extractRedstoneControl) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, this.isExtract, this.extractChannel, extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, this.priority, this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withExtractRedstoneChannel(DyeColor extractRedstoneChannel) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, this.isExtract, this.extractChannel, this.extractRedstoneControl, extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, this.priority, this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withIsRoundRobin(boolean isRoundRobin) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, this.isExtract, this.extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, isRoundRobin, this.isSelfFeed, this.priority, this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withIsSelfFeed(boolean isSelfFeed) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, this.isExtract, this.extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, isSelfFeed, this.priority, this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withPriority(int priority) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, this.isExtract, this.extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, Math.min(9999, Math.max(-9999, priority)), this.extractInterval, this.isBalanced);
    }

    public ItemConduitConnectionConfig withExtractInterval(int extractInterval) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, this.isExtract, this.extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, this.priority, Math.min(MAX_EXTRACT_INTERVAL, Math.max(0, extractInterval)), this.isBalanced);
    }

    public ItemConduitConnectionConfig withIsBalanced(boolean isBalanced) {
        return new ItemConduitConnectionConfig(this.isInsert, this.insertChannel, this.isExtract, this.extractChannel, this.extractRedstoneControl, this.extractRedstoneChannel, this.isRoundRobin, this.isSelfFeed, this.priority, this.extractInterval, isBalanced);
    }

    @Override
    public ConnectionConfigType<ItemConduitConnectionConfig> type() {
        return TYPE;
    }
}
