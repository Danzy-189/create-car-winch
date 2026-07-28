package dev.danzy.carwinch.content.towbar;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.EnumSet;
import java.util.List;

public class TowbarBlockEntity extends SmartBlockEntity
        implements RopeStrandHolderBlockEntity {

    public static final double MAX_COUPLING_DISTANCE = 4.0D;

    private RopeStrandHolderBehavior ropeHolder;

    private BlockPos couplingTarget;
    private boolean couplingOwner;

    private PhysicsConstraintHandle couplingConstraint;

    public TowbarBlockEntity(
            final BlockEntityType<?> type,
            final BlockPos pos,
            final BlockState state
    ) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(
            final List<BlockEntityBehaviour> behaviours
    ) {
        behaviours.add(
                this.ropeHolder = new RopeStrandHolderBehavior(this)
        );
    }

    public RopeStrandHolderBehavior getRopeHolder() {
        return this.ropeHolder;
    }

    @Override
    public RopeStrandHolderBehavior getBehavior() {
        return this.ropeHolder;
    }

    @Override
    public Vec3 getAttachmentPoint(
            final BlockPos pos,
            final BlockState state
    ) {
        final Direction facing = state.getValue(TowbarBlock.FACING);

        return pos.getCenter().add(
                Vec3.atLowerCornerOf(facing.getNormal())
                        .scale(0.38D)
        );
    }

    public Vec3 getAttachmentPoint() {
        return getAttachmentPoint(
                this.worldPosition,
                this.getBlockState()
        );
    }

    public boolean isCoupled() {
        return this.couplingTarget != null;
    }

    public boolean isCouplingOwner() {
        return this.couplingOwner;
    }

    public BlockPos getCouplingTarget() {
        return this.couplingTarget;
    }

    public static boolean createCoupling(
            final TowbarBlockEntity first,
            final TowbarBlockEntity second
    ) {
        if (first == second
                || first.level == null
                || second.level == null
                || first.level != second.level
                || first.isCoupled()
                || second.isCoupled()) {
            return false;
        }

        if (!(first.level instanceof ServerLevel level)) {
            return false;
        }

        final Vec3 firstLocal = first.getAttachmentPoint();
        final Vec3 secondLocal = second.getAttachmentPoint();

        final ServerSubLevel firstSubLevel =
                getContainingSubLevel(level, firstLocal);

        final ServerSubLevel secondSubLevel =
                getContainingSubLevel(level, secondLocal);

        if (firstSubLevel == null
                || secondSubLevel == null
                || firstSubLevel == secondSubLevel) {
            return false;
        }

        final Vec3 firstGlobal =
                Sable.HELPER.projectOutOfSubLevel(level, firstLocal);

        final Vec3 secondGlobal =
                Sable.HELPER.projectOutOfSubLevel(level, secondLocal);

        if (firstGlobal.distanceTo(secondGlobal)
                > MAX_COUPLING_DISTANCE) {
            return false;
        }

        first.couplingTarget = second.worldPosition;
        first.couplingOwner = true;

        second.couplingTarget = first.worldPosition;
        second.couplingOwner = false;

        first.setChanged();
        second.setChanged();

        first.blockEntityNotify();
        second.blockEntityNotify();

        if (first.createPhysicsConstraint()) {
            return true;
        }

        first.couplingTarget = null;
        first.couplingOwner = false;

        second.couplingTarget = null;
        second.couplingOwner = false;

        first.setChanged();
        second.setChanged();

        first.blockEntityNotify();
        second.blockEntityNotify();

        return false;
    }

    private static ServerSubLevel getContainingSubLevel(
            final ServerLevel level,
            final Vec3 position
    ) {
        final Object containing =
                Sable.HELPER.getContaining(level, position);

        return containing instanceof ServerSubLevel serverSubLevel
                ? serverSubLevel
                : null;
    }

    private void blockEntityNotify() {
        if (this.level == null) {
            return;
        }

        this.level.sendBlockUpdated(
                this.worldPosition,
                this.getBlockState(),
                this.getBlockState(),
                Block.UPDATE_CLIENTS
        );
    }

    private boolean createPhysicsConstraint() {
        if (!this.couplingOwner
                || this.couplingTarget == null
                || !(this.level instanceof ServerLevel level)) {
            return false;
        }

        final TowbarBlockEntity target =
                getTowbar(level, this.couplingTarget);

        if (target == null) {
            return false;
        }

        final Vec3 localA = this.getAttachmentPoint();
        final Vec3 localB = target.getAttachmentPoint();

        final ServerSubLevel subLevelA =
                getContainingSubLevel(level, localA);

        final ServerSubLevel subLevelB =
                getContainingSubLevel(level, localB);

        if (subLevelA == null
                || subLevelB == null
                || subLevelA == subLevelB) {
            return false;
        }

        final Vec3 globalA =
                Sable.HELPER.projectOutOfSubLevel(level, localA);

        final Vec3 globalB =
                Sable.HELPER.projectOutOfSubLevel(level, localB);

        final double distance = globalA.distanceTo(globalB);

        if (distance > MAX_COUPLING_DISTANCE) {
            return false;
        }

        if (distance < 0.0001D) {
            return false;
        }

        /*
         * Направление оси сцепки в мировой системе.
         * Ось Z joint frame направлена вдоль вала.
         */
        final Vector3d worldDirection = new Vector3d(
                globalB.x - globalA.x,
                globalB.y - globalA.y,
                globalB.z - globalA.z
        ).normalize();

        /*
         * Один и тот же мировой вектор нужно перевести
         * в локальную систему каждого sublevel отдельно.
         */
        final Vector3d localDirectionA =
                subLevelA.logicalPose()
                        .transformNormalInverse(worldDirection);

        final Vector3d localDirectionB =
                subLevelB.logicalPose()
                        .transformNormalInverse(worldDirection);

        final Quaterniond frameA =
                new Quaterniond().rotationTo(
                        new Vector3d(0.0D, 0.0D, 1.0D),
                        localDirectionA
                );

        final Quaterniond frameB =
                new Quaterniond().rotationTo(
                        new Vector3d(0.0D, 0.0D, 1.0D),
                        localDirectionB
                );

        /*
         * LINEAR_X/Y/Z:
         *   запрещают растяжение, сжатие и боковое смещение.
         *
         * ANGULAR_Z:
         *   запрещает вращение вокруг продольной оси вала.
         *
         * ANGULAR_X/Y не заблокированы:
         *   сцепка может ходить вверх/вниз и влево/вправо,
         *   как шарнир между прицепом и тягачом.
         */
        final GenericConstraintConfiguration configuration =
                new GenericConstraintConfiguration(
                        new Vector3d(localA.x, localA.y, localA.z),
                        new Vector3d(localB.x, localB.y, localB.z),
                        frameA,
                        frameB,
                        EnumSet.of(
                                ConstraintJointAxis.LINEAR_X,
                                ConstraintJointAxis.LINEAR_Y,
                                ConstraintJointAxis.LINEAR_Z,
                                ConstraintJointAxis.ANGULAR_Z
                        )
                );

        final ServerSubLevelContainer container =
                getServerContainer(level);

        if (container == null) {
            return false;
        }

        final SubLevelPhysicsSystem physicsSystem =
                container.physicsSystem();

        final PhysicsPipeline pipeline =
                physicsSystem.getPipeline();

        removePhysicsConstraint();

        this.couplingConstraint =
                pipeline.addConstraint(
                        subLevelA,
                        subLevelB,
                        configuration
                );

        if (this.couplingConstraint == null) {
            return false;
        }

        this.couplingConstraint.setContactsEnabled(false);

        return this.couplingConstraint.isValid();
    }

    private static ServerSubLevelContainer getServerContainer(
            final ServerLevel level
    ) {
        final SubLevelContainer container =
                SubLevelContainer.getContainer(level);

        return container instanceof ServerSubLevelContainer serverContainer
                ? serverContainer
                : null;
    }

    private static TowbarBlockEntity getTowbar(
            final Level level,
            final BlockPos pos
    ) {
        return level.getBlockEntity(pos)
                instanceof TowbarBlockEntity towbar
                ? towbar
                : null;
    }

    private void removePhysicsConstraint() {
        if (this.couplingConstraint == null) {
            return;
        }

        this.couplingConstraint.remove();
        this.couplingConstraint = null;
    }

    public void detachCoupling() {
        if (this.level != null) {
            detachCoupling(this.level, this.worldPosition);
        }
    }

    public static void detachCoupling(
            final Level level,
            final BlockPos pos
    ) {
        final TowbarBlockEntity first =
                getTowbar(level, pos);

        if (first == null) {
            return;
        }

        final BlockPos targetPos =
                first.couplingTarget;

        first.removePhysicsConstraint();
        first.couplingTarget = null;
        first.couplingOwner = false;
        first.setChanged();
        first.blockEntityNotify();

        if (targetPos == null) {
            return;
        }

        final TowbarBlockEntity second =
                getTowbar(level, targetPos);

        if (second == null) {
            return;
        }

        second.removePhysicsConstraint();
        second.couplingTarget = null;
        second.couplingOwner = false;
        second.setChanged();
        second.blockEntityNotify();
    }

    @Override
    public void remove() {
        detachCoupling();
        super.remove();
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level == null
                || this.level.isClientSide) {
            return;
        }

        final BlockState state = this.getBlockState();

        if (state.hasProperty(TowbarBlock.HOOKED)) {
            final boolean hooked =
                    this.ropeHolder.isAttached();

            if (state.getValue(TowbarBlock.HOOKED) != hooked) {
                this.level.setBlock(
                        this.worldPosition,
                        state.setValue(
                                TowbarBlock.HOOKED,
                                hooked
                        ),
                        Block.UPDATE_CLIENTS
                );
            }
        }

        if (!this.couplingOwner
                || this.couplingTarget == null) {
            return;
        }

        if (this.couplingConstraint == null
                || !this.couplingConstraint.isValid()) {
            this.createPhysicsConstraint();
        }
    }

    @Override
    protected void write(
            final CompoundTag tag,
            final HolderLookup.Provider registries,
            final boolean clientPacket
    ) {
        super.write(tag, registries, clientPacket);

        if (this.couplingTarget != null) {
            tag.putLong(
                    "CouplingTarget",
                    this.couplingTarget.asLong()
            );
        }

        tag.putBoolean(
                "CouplingOwner",
                this.couplingOwner
        );
    }

    @Override
    protected void read(
            final CompoundTag tag,
            final HolderLookup.Provider registries,
            final boolean clientPacket
    ) {
        super.read(tag, registries, clientPacket);

        this.couplingTarget =
                tag.contains("CouplingTarget")
                        ? BlockPos.of(
                                tag.getLong("CouplingTarget")
                        )
                        : null;

        this.couplingOwner =
                tag.getBoolean("CouplingOwner");
    }
}
