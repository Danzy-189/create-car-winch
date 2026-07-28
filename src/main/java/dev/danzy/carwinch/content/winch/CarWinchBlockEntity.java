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
import net.minecraft.world.level.Level;
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
    /** Blocks of rope actively paid out per tick at full redstone strength. */
    public static final float RELEASE_SPEED = 0.12F;
    /** Blocks of rope let out per tick when the rope is taut and the winch is idle (freewheel). */
    public static final float PAYOUT_SPEED = 0.16F;
    /** How much slack we tolerate before freewheeling. */
    public static final double SLACK_TOLERANCE = 1.03;
    public static final double RENDER_BOUNDING_BOX_INFLATION = 8.0;
    /** Cosmetic drum spin, degrees per tick at full strength. */
    private static final float DRUM_SPIN_PER_TICK = 14.0F;

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

    /**
     * Сигнал, приходящий в блок с указанной стороны.
     */
    private static int signalFrom(final Level level, final BlockPos pos, final Direction side) {
        return level.getSignal(pos.relative(side), side);
    }

    /**
     * Куда и насколько сильно работает лебёдка.
     *
     * Сигнал сверху (или с боков) - тянет трос внутрь.
     * Сигнал снизу - травит трос наружу.
     * Оба одновременно и одинаковой силы - тормоз, трос стоит на месте.
     *
     * Результат: положительное значение - натяг, отрицательное - выдача,
     * 0 - лебёдка ничего не делает (обрабатывается отдельно).
     * Диапазон -15..15.
     */
    public static int getCommandedPower(final Level level, final BlockPos pos) {
        int pull = signalFrom(level, pos, Direction.UP);
        for (final Direction side : Direction.Plane.HORIZONTAL) {
            pull = Math.max(pull, signalFrom(level, pos, side));
        }
        final int release = signalFrom(level, pos, Direction.DOWN);
        return pull - release;
    }

    /** true, если сигналы с двух сторон гасят друг друга - трос заблокирован. */
    private static boolean isBraked(final Level level, final BlockPos pos) {
        return getCommandedPower(level, pos) == 0
                && (signalFrom(level, pos, Direction.DOWN) > 0
                    || signalFrom(level, pos, Direction.UP) > 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level == null) {
            return;
        }

        if (this.level.isClientSide) {
            this.invalidateRenderBoundingBox();
            // сначала применяем скорость, посчитанную в прошлом тике, потом обновляем её
            this.drumAngle.setValue(this.drumAngle.getValue() + this.clientDrumSpeed);
            final int commanded = getCommandedPower(this.level, this.worldPosition);
            this.clientDrumSpeed = DRUM_SPIN_PER_TICK * (commanded / 15.0F);
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
     * Сверху - наматывает, снизу - размотывает.
     * Без сигнала лебёдка на свободном ходу и травит трос, когда он натянулся,
     * чтобы буксируемое всё ещё могло уехать.
     */
    private void updateRopeStrandExtension(final ServerRopeStrand strand) {
        final double desiredExtension = strand.getExtension()
                + (strand.getPoints().size() - 2) * ServerRopeStrand.SEGMENT_LENGTH;
        final double currentExtension = strand.getCurrentExtension();

        final int commanded = getCommandedPower(this.level, this.worldPosition);

        float movementSpeed;
        if (commanded > 0) {
            movementSpeed = -REEL_SPEED * (commanded / 15.0F);
        } else if (commanded < 0) {
            movementSpeed = RELEASE_SPEED * (-commanded / 15.0F);
        } else if (isBraked(this.level, this.worldPosition)) {
            return;
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
                extension -= ServerRopeStrand.SEGMENT_LENGTH;
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
