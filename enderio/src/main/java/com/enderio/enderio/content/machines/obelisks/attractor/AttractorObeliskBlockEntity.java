package com.enderio.enderio.content.machines.obelisks.attractor;

import com.enderio.enderio.api.EnderIOCapabilities;
import com.enderio.core.annotations.UseOnly;
import com.enderio.enderio.api.capacitor.CapacitorModifier;
import com.enderio.enderio.api.capacitor.LinearScalable;
import com.enderio.enderio.api.capacitor.QuadraticScalable;
import com.enderio.enderio.api.filter.SoulFilter;
import com.enderio.enderio.api.io.energy.EnergyIOMode;
import com.enderio.enderio.config.machines.MachinesConfig;
import com.enderio.enderio.content.machines.obelisks.ObeliskAreaManager;
import com.enderio.enderio.content.machines.obelisks.ObeliskBlockEntity;
import com.enderio.enderio.foundation.block.entity.flags.CapacitorSupport;
import com.enderio.enderio.foundation.inventory.MachineInventoryLayout;
import com.enderio.enderio.init.EIOBlockEntities;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class AttractorObeliskBlockEntity extends ObeliskBlockEntity<AttractorObeliskBlockEntity> {

    private static final QuadraticScalable ENERGY_CAPACITY = new QuadraticScalable(CapacitorModifier.ENERGY_CAPACITY,
            MachinesConfig.COMMON.ENERGY.ATTRACTOR_CAPACITY);
    private static final LinearScalable ENERGY_USAGE = new LinearScalable(CapacitorModifier.ENERGY_USE,
            MachinesConfig.COMMON.ENERGY.ATTRACTOR_USAGE);
    private static final LinearScalable RANGE = new LinearScalable(CapacitorModifier.ENERGY_USE,
            MachinesConfig.COMMON.ATTRACTOR_RANGE);

    private static final EntityTypeTest<Entity, Mob> MOB_TYPE = EntityTypeTest.forClass(Mob.class);
    private final Vec3 targetPos;

    @UseOnly(LogicalSide.SERVER)
    @Nullable
    private FakePlayer fakePlayer;

    public AttractorObeliskBlockEntity(BlockPos worldPosition, BlockState blockState) {
        super(EIOBlockEntities.ATTRACTOR_OBELISK.get(), worldPosition, blockState, false, CapacitorSupport.REQUIRED,
                EnergyIOMode.Input, ENERGY_CAPACITY, ENERGY_USAGE);
        targetPos = new Vec3(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5);
    }

    @UseOnly(LogicalSide.SERVER)
    private FakePlayer getFakePlayer() {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("Level is null");
        }

        if (fakePlayer == null) {
            fakePlayer = new FakePlayer(serverLevel, new GameProfile(getMachineOwnerOrRandom(), "enderio:attractor:" + worldPosition));
            fakePlayer.setPos(targetPos.x, targetPos.y, targetPos.z);
        }

        return fakePlayer;
    }

    @Override
    protected @Nullable ObeliskAreaManager<AttractorObeliskBlockEntity> getAreaManager(ServerLevel level) {
        return null;
    }

    @Override
    public @Nullable MachineInventoryLayout createInventoryLayout() {
        return MachineInventoryLayout.builder()
                .inputSlot((integer, itemStack) -> itemStack.getCapability(EnderIOCapabilities.SOUL_FILTER) != null)
                .slotAccess(FILTER)
                .capacitor()
                .build();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new AttractorObeliskMenu(containerId, playerInventory, this);
    }

    @Override
    public int getMaxRange() {
        return RANGE.scaleI(this::getCapacitorData).get();
    }

    @Override
    public String getColor() {
        return MachinesConfig.CLIENT.BLOCKS.ATTRACTOR_RANGE_COLOR.get();
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (isActive()) {
            doAttract();
        }
    }

    private void doAttract() {
        if (level == null) {
            return;
        }
        AABB aabb = getAABB();
        if (aabb == null) {
            return;
        }
        SoulFilter filter = getSoulFilter();
        if (filter == null) {
            return;
        }
        List<Mob> filteredEntities = level.getEntities(MOB_TYPE, aabb, filter::test);
        boolean pullBosses = MachinesConfig.COMMON.ATTRACTOR_PULL_BOSSES.get();
        for (Mob mob : filteredEntities) {
            if (!pullBosses && mob.getType().is(Tags.EntityTypes.BOSSES)) {
                // ignore
            } else if (mob instanceof WitherBoss) {
                mob.goalSelector.disableControlFlag(Goal.Flag.TARGET);
                mob.goalSelector.disableControlFlag(Goal.Flag.LOOK);
                mob.setTarget(null);
                directPull(mob, 2.25f);
            } else if (mob instanceof PathfinderMob) {
                attractMob(mob);
            } else if (useTarget(mob)) {
                setTarget(mob);
            } else if (mob instanceof Ghast) {
                directPull(mob, 1);
            }
        }
    }

    private boolean useTarget(Mob mob) {
        return mob instanceof Slime || mob instanceof Phantom;
    }

    private void setTarget(Mob mob) {
        assert level != null;
        mob.setTarget(getFakePlayer());
    }

    private void attractMob(Mob mob) {
        mob.goalSelector.enableControlFlag(Goal.Flag.MOVE);
        // Keep the mob one block away from the obelisk.
        Vec3 direction = targetPos.subtract(mob.position()).normalize();
        double x = targetPos.x - direction.x;
        double y = targetPos.y - direction.y;
        double z = targetPos.z - direction.z;
        var navigation = mob.getNavigation();
        if (navigation.getClass() == GroundPathNavigation.class && navigation.isInProgress() && mob.onGround()
                && mob.getY() >= mob.level().getMinBuildHeight() && isCurrentGroundTarget(mob, x, y, z)) {
            // Retain vanilla's speed, trimming and stuck timer updates on the same path.
            navigation.moveTo(navigation.getPath(), 1);
        } else {
            navigation.moveTo(x, y, z, 1);
        }
    }

    private boolean isCurrentGroundTarget(Mob mob, double x, double y, double z) {
        BlockPos target = mob.getNavigation().getTargetPos();
        if (target == null || !target.equals(BlockPos.containing(x, y, z))) {
            return false;
        }
        var level = mob.level();
        var chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(target.getX()),
                SectionPos.blockToSectionCoord(target.getZ()));
        if (chunk == null || target.getY() <= level.getMinBuildHeight() + 1) {
            return false;
        }
        // GroundPathNavigation shifts solid or unsupported targets vertically before comparing paths.
        var state = chunk.getBlockState(target);
        return !state.isSolid() && (!state.isAir() || !chunk.getBlockState(target.below()).isAir());
    }

    private void directPull(LivingEntity mob, float speed) {
        Vec3 dir = targetPos.subtract(new Vec3(mob.xo, mob.yo, mob.zo)).normalize();
        dir = dir.scale(speed);
        AABB aabb = mob.getBoundingBox();
        aabb = aabb.move(dir);
        if (level != null && level.noCollision(mob, aabb)) {
            mob.setDeltaMovement(dir);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();

        if (level != null && !level.isClientSide) {
            if (fakePlayer != null) {
                fakePlayer.discard();
                fakePlayer = null;
            }
        }
    }
}
