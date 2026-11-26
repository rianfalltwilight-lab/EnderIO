package com.enderio.enderio.foundation.network.packets;

import com.enderio.enderio.EnderIO;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ClientboundCapacitorBankValuesPacket(UUID uuid, long storedEnergy, long capacity, long added, long send) implements CustomPacketPayload {
    public static final Type<ClientboundCapacitorBankValuesPacket> TYPE = new Type<>(EnderIO.rl("capacitor_bank_values"));
    public static final StreamCodec<ByteBuf, ClientboundCapacitorBankValuesPacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, ClientboundCapacitorBankValuesPacket::uuid,
        ByteBufCodecs.VAR_LONG, ClientboundCapacitorBankValuesPacket::storedEnergy,
        ByteBufCodecs.VAR_LONG, ClientboundCapacitorBankValuesPacket::capacity,
        ByteBufCodecs.VAR_LONG, ClientboundCapacitorBankValuesPacket::added,
        ByteBufCodecs.VAR_LONG, ClientboundCapacitorBankValuesPacket::send,
        ClientboundCapacitorBankValuesPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
