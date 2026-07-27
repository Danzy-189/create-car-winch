package dev.danzy.carwinch.content.towbar;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class TowbarBlockEntity extends SmartBlockEntity implements RopeStrandHolderBlockEntity {

    private RopeStrandHolderBehavior ropeHolder;

    public TowbarBlockEntity(final BlockEntityType<?> type, final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        behaviours.add(this.ropeHolder = new RopeStrandHolderBehavior(this));
    }

    public RopeStrandHolderBehavior getRopeHolder() {
        return this.ropeHolder;
    }

    @Override
    public RopeStrandHolderBehavior getBehavior() {
        return this.ropeHolder;
    }

    @Override
    public Vec3 getAttachmentPoint(final BlockPos pos, final BlockState state) {
        final Direction facing = state.getValue(TowbarBlock.FACING);
        return pos.getCenter().add(Vec3.atLowerCornerOf(facing.getNormal()).scale(0.38));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        final BlockState state = this.getBlockState();
        if (!state.hasProperty(TowbarBlock.HOOKED)) {
            return;
        }

        final boolean hooked = this.ropeHolder.isAttached();
        if (state.getValue(TowbarBlock.HOOKED) != hooked) {
            this.level.setBlock(this.worldPosition, state.setValue(TowbarBlock.HOOKED, hooked), Block.UPDATE_ALL);
        }
    }
}
