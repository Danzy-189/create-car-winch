package dev.danzy.carwinch.content.winch;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;

public class CarWinchBlockEntity extends SmartBlockEntity implements RopeStrandHolderBlockEntity {

    /** Hard cap on rope length, in blocks. */
    public static final double MAX_RANGE = 48.0;
    /** Blocks of rope pulled in per tick at full redstone strength. */
    public static final float REEL_SPEED = 0.09F;
    /** Blocks of rope let out per tick when the rope is taut and the winch is idle (freewheel). */
    public static final float PAYOUT_SPEED = 0.16F;
    /** How much slack we tolerate before freewheeling. */
    public static final double SLACK_TOLERANCE = 1.03;
    public static final double RENDER_BOUNDING_BOX_INFLATION = 8.0;

    private RopeStrandHolderBehavior ropeHolder;

    /** Purely cosmetic drum spin, driven client side. */
    public final LerpedFloat drumAngle = LerpedFloat.angular();
    private float clientDrumSpeed;

    public CarWinchBlockEntity(final BlockEntityType<?> type, final BlockPos pos, final BlockState state) {
        super(type, pos, state);
        this.setLazyTickRate(20);
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

    /** Where the rope physically leaves the drum. */
    @Override
    public Vec3 getAttachmentPoint(final BlockPos pos, final BlockState state) {
        final Direction facing = state.getValue(CarWinchBlock.FACING);
        return pos.getCenter().add(Vec3.atLowerCornerOf(facing.getNormal()).scale(0.44));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level == null) {
            return;
        }

        if (this.level.isClientSide) {
            this.invalidateRenderBoundingBox();
            this.drumAngle.setValue(this.drumAngle.getValue() + this.clientDrumSpeed);
            this.clientDrumSpeed = this.getBlockState().getValue(CarWinchBlock.POWERED) ? 14.0F : 0.0F;
            return;
        }

        this.syncRopedState();

        final ServerRopeStrand strand = this.ropeHolder.getOwnedStrand();
        if (strand != null && this.ropeHolder.ownsRope()) {
            this.updateRopeStrandExtension(strand);
        }
    }

    /** Swaps winch.bbmodel <-> winch_1.bbmodel depending on whether a rope is spooled. */
    private void syncRopedState() {
        final BlockState state = this.getBlockState();
        if (!state.hasProperty(CarWinchBlock.ROPED)) {
            return;
        }
        final boolean roped = this.ropeHolder.isAttached();
        if (state.getValue(CarWinchBlock.ROPED) != roped) {
            this.level.setBlock(this.worldPosition, state.setValue(CarWinchBlock.ROPED, roped), Block.UPDATE_ALL);
        }
    }

    /**
     * Redstone pulls the rope in. With no signal the winch freewheels, paying rope out when it goes taut
     * so the towed contraption can still drive away.
     */
    private void updateRopeStrandExtension(final ServerRopeStrand strand) {
        final int power = this.level.getBestNeighborSignal(this.worldPosition);

        final double desiredExtension = strand.getExtension()
                + (strand.getPoints().size() - 2) * ServerRopeStrand.SEGMENT_LENGTH;
        final double currentExtension = strand.getCurrentExtension();

        float movementSpeed;
        if (power > 0) {
            movementSpeed = -REEL_SPEED * (power / 15.0F);
        } else if (currentExtension > desiredExtension * SLACK_TOLERANCE) {
            movementSpeed = PAYOUT_SPEED;
        } else {
            return;
        }

        if (currentExtension > MAX_RANGE) {
            movementSpeed = Math.min(0.0F, movementSpeed);
        }

        double extension = strand.getExtension() + movementSpeed;
        final int minPointCount = 2;

        if (extension < 1.0 && strand.getPoints().size() == minPointCount) {
            extension = 1.0;
        } else {
            while (extension < 0.0) {
                strand.removeFirstPoint();
                extension += ServerRopeStrand.SEGMENT_LENGTH;

                if (extension < 1.0 && strand.getPoints().size() == minPointCount) {
                    extension = 1.0;
                    break;
                }
            }

            while (extension > ServerRopeStrand.SEGMENT_LENGTH) {
                final Vec3 anchor = Sable.HELPER.projectOutOfSubLevel(this.level, this.ropeHolder.getAttachmentPoint());
                strand.addPoint(new Vector3d(anchor.x, anchor.y, anchor.z));
                extension -= 1.0;
            }

            if (extension < 1.0 && strand.getPoints().size() <= minPointCount) {
                extension = 1.0;
            }
        }

        strand.updateFirstSegmentExtension(extension);
    }

    @Override
    public AABB getRenderBoundingBox() {
        final ClientRopeStrand rope = this.ropeHolder.getClientStrand();
        if (rope != null && this.ropeHolder.ownsRope()) {
            final AABB bounds = rope.getBounds();
            if (bounds != null) {
                return bounds.inflate(RENDER_BOUNDING_BOX_INFLATION);
            }
        }
        return super.getRenderBoundingBox();
    }
}
