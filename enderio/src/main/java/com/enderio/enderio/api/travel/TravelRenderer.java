package com.enderio.enderio.api.travel;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;

public interface TravelRenderer<T extends TravelTarget> {
    /** Custom renderers may draw beyond their target block. Opt into culling when the geometry is known. */
    default boolean isVisible(T target, Camera camera, Frustum frustum, boolean active) {
        return true;
    }

    /**
     * @param poseStack Pose relative to the block being rendered
     */
    void render(T travelData, LevelRenderer levelRenderer, PoseStack poseStack, double distanceSquared, boolean active, float partialTick);
}
