package com.enderio.enderio.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(value = Dist.CLIENT)
public class SoundHandler {

    public static final Long2ObjectMap<SoundInstance> SOUND_MAP = new Long2ObjectOpenHashMap<>();

    public static void playSound(BlockPos pos, SoundEvent soundEvent, SoundSource source, float volume, float pitch, RandomSource random, double x, double y, double z) {
        SoundInstance sound = SOUND_MAP.get(pos.asLong());
        if (sound == null || !isActive(sound)) {
            sound = new SimpleSoundInstance(soundEvent, source, volume, pitch, random, x, y, z);
            SOUND_MAP.put(pos.asLong(), sound);
            Minecraft.getInstance().getSoundManager().play(sound);
        }
    }

    public static void playSound(BlockPos pos, SoundEvent soundEvent, SoundSource source, double volume, float pitch, RandomSource random, double x, double y, double z) {
        SoundHandler.playSound(pos, soundEvent, source, (float) volume, pitch, random, x, y, z);
    }

    public static void stopSound(BlockPos pos) {
        SoundInstance sound = SOUND_MAP.remove(pos.asLong());
        if (sound != null && isActive(sound)) {
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            SOUND_MAP.clear();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SOUND_MAP.clear();
    }

    public static boolean isActive(SoundInstance sound) {
        return Minecraft.getInstance().getSoundManager().isActive(sound);
    }
}
