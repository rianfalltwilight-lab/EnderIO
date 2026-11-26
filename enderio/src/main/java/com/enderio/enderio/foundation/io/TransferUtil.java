package com.enderio.enderio.foundation.io;

import com.enderio.enderio.api.io.IOMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;

public class TransferUtil {

    // region Items

    public static void distributeItems(IOMode mode, IItemHandler selfItemHandler, IItemHandler otherItemHandler) {
        distributeItems(mode.canPush(), mode.canPull(), selfItemHandler, otherItemHandler);
    }

    public static void distributeItems(boolean canPush, boolean canPull, IItemHandler selfItemHandler, IItemHandler otherItemHandler) {
        if (canPush) {
            moveItems(selfItemHandler, otherItemHandler);
        }

        if (canPull) {
            moveItems(otherItemHandler, selfItemHandler);
        }
    }

    private static void moveItems(IItemHandler from, IItemHandler to) {
        for (int i = 0; i < from.getSlots(); i++) {
            if (from.getStackInSlot(i).isEmpty()) {
                continue;
            }

            ItemStack extracted = ItemStack.EMPTY;
            for (int j = 0; j < to.getSlots(); j++) {
                if (extracted.isEmpty()) {
                    extracted = from.extractItem(i, from.getSlotLimit(i), true);
                    if (extracted.isEmpty()) {
                        break;
                    }
                }

                ItemStack remainder = to.insertItem(j, extracted, false);
                int successfullyMoved = extracted.getCount() - remainder.getCount();
                if (successfullyMoved > 0) {
                    from.extractItem(i, successfullyMoved, false);
                    if (remainder.isEmpty()) {
                        break;
                    }
                    // The source can expose a different item or extraction limit after a transfer.
                    extracted = ItemStack.EMPTY;
                }
            }
        }
    }

    // endregion

    // region Fluids

    public static void distributeFluids(IOMode mode, IFluidHandler selfItemHandler, IFluidHandler otherItemHandler) {
        distributeFluids(mode.canPush(), mode.canPull(), selfItemHandler, otherItemHandler);
    }

    public static void distributeFluids(boolean canPush, boolean canPull, IFluidHandler selfItemHandler, IFluidHandler otherItemHandler) {
        // TODO: Do we want to imitate old behaviour where if we have no fluid, we pull by default?

        if (canPush) {
            int filled = 0;
            for (int i = 0; i < selfItemHandler.getTanks(); i++) {
                filled += FluidUtil.tryFluidTransfer(otherItemHandler, selfItemHandler, selfItemHandler.getFluidInTank(i).copyWithAmount(Integer.MAX_VALUE), true).getAmount();
            }
            if (filled > 0) {
                return;
            }
        }

        if (canPull) {
            for (int i = 0; i < selfItemHandler.getTanks(); i++) {
                if (selfItemHandler.getFluidInTank(i).isEmpty()) {
                    FluidUtil.tryFluidTransfer(selfItemHandler, otherItemHandler, Integer.MAX_VALUE, true);
                } else {
                    FluidUtil.tryFluidTransfer(selfItemHandler, otherItemHandler, selfItemHandler.getFluidInTank(i).copyWithAmount(Integer.MAX_VALUE), true);
                }
            }
        }
    }

    // endregion

    // region Even Distribution

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ThreadLocal<EnergyTransfers> ENERGY_TRANSFERS = ThreadLocal.withInitial(EnergyTransfers::new);

    private static final class EnergyTransfers {
        final IEnergyStorage[] sources = new IEnergyStorage[DIRECTIONS.length];
        final IEnergyStorage[] receivers = new IEnergyStorage[DIRECTIONS.length];
        boolean inUse;
    }

    /**
     * Distributes energy evenly to all neighboring blocks that can receive it.
     * @apiNote Assumes that the source block has a single energy buffer shared across all sides.
     * @param level the level containing the source block
     * @param pos the position of the source
     * @param canPushTo whether each side allows pushing
     */
    public static void distributeEnergyEvenly(Level level, BlockPos pos, Function<Direction, Boolean> canPushTo) {
        distributeEnergyEvenly(null, canPushTo, side -> level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side),
            side -> level.getCapability(Capabilities.EnergyStorage.BLOCK, pos.relative(side), side.getOpposite()));
    }

    /**
     * Machine variant with a shared energy buffer and cached, side-specific capability lookups.
     * The shared buffer is only used to skip empty machines. All transfers use the exposed sided handlers.
     */
    public static void distributeEnergyEvenly(@Nullable IEnergyStorage shared, Function<Direction, Boolean> canPushTo,
        Function<Direction, @Nullable IEnergyStorage> sourceAt, Function<Direction, @Nullable IEnergyStorage> receiverAt) {
        if (shared != null && shared.getEnergyStored() <= 0) {
            return;
        }

        var transfers = ENERGY_TRANSFERS.get();
        if (transfers.inUse) {
            // Neighbor callbacks can trigger a nested distribution before the outer snapshot is consumed.
            transfers = new EnergyTransfers();
        }
        transfers.inUse = true;
        int count = 0;
        try {
            for (Direction direction : DIRECTIONS) {
                if (!canPushTo.apply(direction)) {
                    continue;
                }
                var source = sourceAt.apply(direction);
                if (source == null || !source.canExtract()) {
                    continue;
                }
                var receiver = receiverAt.apply(direction);
                if (receiver != null && receiver != source && receiver.canReceive()) {
                    transfers.sources[count] = source;
                    transfers.receivers[count] = receiver;
                    count++;
                }
            }

            if (count == 0) {
                return;
            }
            int remaining = transfers.sources[0].extractEnergy(Integer.MAX_VALUE, true);
            for (int i = 0; remaining > 0 && i < count; i++) {
                int sidesLeft = count - i;
                int share = remaining <= sidesLeft ? remaining : remaining / sidesLeft;
                int inserted = transfers.receivers[i].receiveEnergy(share, false);
                if (inserted > 0) {
                    transfers.sources[i].extractEnergy(inserted, false);
                    remaining -= inserted;
                }
            }
        } finally {
            Arrays.fill(transfers.sources, 0, count, null);
            Arrays.fill(transfers.receivers, 0, count, null);
            transfers.inUse = false;
        }
    }

    public static int distributeEnergyEvenlyBetween(int availableEnergy, Set<IEnergyStorage> receivers) {
        // Abort if we have no valid pairs
        if (receivers.isEmpty()) {
            return 0;
        }

        // Distribute evenly
        int energyRemaining = availableEnergy;
        int toShareWith = receivers.size();

        for (IEnergyStorage receiver : receivers) {
            // If we have too little energy left, just give it to the first handler that will accept it all
            int shareAmount;
            if (energyRemaining <= toShareWith) {
                shareAmount = energyRemaining;
            } else {
                shareAmount = energyRemaining / toShareWith;
            }

            int inserted = receiver.receiveEnergy(shareAmount, false);
            energyRemaining -= inserted;

            toShareWith--;
            if (energyRemaining <= 0) {
                break;
            }
        }

        return availableEnergy - energyRemaining;
    }

    // endregion
}
