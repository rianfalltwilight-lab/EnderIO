package com.enderio.enderio.client.content.travel;

import com.enderio.enderio.content.travel.TravelHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.Month;
import java.time.ZoneId;

@EventBusSubscriber(value = Dist.CLIENT)
public class TravelParticleHandler {
    private static final float[] PURPLE = {117 / 255f, 7 / 255f, 135 / 255f};
    private static final float[] WHITE = {1, 1, 1};
    private static final float[][] JUNE_COLORS = {
        {228 / 255f, 3 / 255f, 3 / 255f}, {1, 140 / 255f, 0}, {1, 237 / 255f, 0},
        {0, 128 / 255f, 38 / 255f}, {0, 77 / 255f, 1}, PURPLE, WHITE, WHITE, WHITE, WHITE
    };
    private static long dayStart, nextDay;
    private static boolean june;
    private static int tick = 0;

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post e) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            tick++;
            if (tick % 3 == 0 && player.onGround() && player.isShiftKeyDown() && TravelHandler.canItemTeleport(player)) {
                TravelHandler.teleportPosition(player.level(), player).ifPresent(TravelParticleHandler::addTravelParticle);
            }
        }
    }

    private static void addTravelParticle(Vec3 pos) {
        int time = tick/3%20;
        float x = Mth.sin((float)(time*Math.PI)/10f);
        float z = Mth.cos((float)(time*Math.PI)/10f);
        @Nullable Particle particle = Minecraft.getInstance().levelRenderer.addParticleInternal(
            ParticleTypes.TOTEM_OF_UNDYING, false, true, pos.x() + x, pos.y() + 0.4, pos.z() + z, 0, 0, 0);
        if (particle != null) {
            particle.setLifetime(30);
            var color = getTravelParticleColor();
            particle.gravity = 0;
            particle.setColor(color[0], color[1], color[2]);
        }
    }

    private static float[] getTravelParticleColor() {
        long now = System.currentTimeMillis();
        if (now < dayStart || now >= nextDay) {
            ZoneId zone = ZoneId.systemDefault();
            var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
            nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            june = date.getMonth() == Month.JUNE;
        }
        if (!june) {
            return PURPLE;
        }
        int index = tick / 3 % JUNE_COLORS.length;
        return index < 0 ? WHITE : JUNE_COLORS[index];
    }
}
