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
    private static final double MIN_COUPLING_DISTANCE = 0.05D;

    private RopeStrandHolderBehavior ropeHolder;

    private BlockPos couplingTarget;
    private boolean couplingOwner;

    /**
     * Длина фиксируется в момент соединения.
     * После этого вал не меняет размер.
     */
    private double couplingLength;

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
        return couplingTarget != null;
    }

    public boolean isCouplingOwner() {
        return couplingOwner;
    }

    public BlockPos getCouplingTarget() {
        return couplingTarget;
    }

    /**
     * Возвращает длину сцепки для рендера.
     */
    public double getCouplingLength() {
        return couplingLength;
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

        final Vec3 firstGlobal =
                Sable.HELPER.projectOutOfSubLevel(level, firstLocal);

        final Vec3 secondGlobal =
                Sable.HELPER.projectOutOfSubLevel(level, secondLocal);

        final Vec3 globalDelta = secondGlobal.subtract(firstGlobal);
        final double distance = globalDelta.length();

        if (distance < MIN_COUPLING_DISTANCE
                || distance > MAX_COUPLING_DISTANCE) {
            return false;
        }

        final ServerSubLevel firstSubLevel =
                getContainingSubLevel(level, firstLocal);

        final ServerSubLevel secondSubLevel =
                getContainingSubLevel(level, secondLocal);

        if (firstSubLevel == null
                || secondSubLevel == null
                || firstSubLevel == secondSubLevel) {
            return false;
        }

        first.couplingTarget = second.worldPosition;
        first.couplingOwner = true;
        first.couplingLength = distance;

        second.couplingTarget = first.worldPosition;
        second.couplingOwner = false;
        second.couplingLength = distance;

        first.setChanged();
        second.setChanged();

        first.blockEntityNotify();
        second.blockEntityNotify();

        /*
         * Constraint строится в исходной геометрии.
         * Никакой принудительной длины 2 блока здесь нет,
         * поэтому sublevel не телепортируются при клике.
         */
        if (first.createPhysicsConstraint()) {
            return true;
        }

        first.clearCouplingData();
        second.clearCouplingData();

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

    private void clearCouplingData() {
        removePhysicsConstraint();
        couplingTarget = null;
        couplingOwner = false;
        couplingLength = 0.0D;
    }

    private void blockEntityNotify() {
        if (level == null) {
            return;
        }

        level.sendBlockUpdated(
                worldPosition,
                getBlockState(),
                getBlockState(),
                Block.UPDATE_CLIENTS
        );
    }

    private boolean createPhysicsConstraint() {
        if (!couplingOwner
                || couplingTarget == null
                || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        final TowbarBlockEntity target =
                getTowbar(serverLevel, couplingTarget);

        if (target == null) {
            return false;
        }

        final Vec3 localA = getAttachmentPoint();
        final Vec3 localB = target.getAttachmentPoint();

        final ServerSubLevel subLevelA =
                getContainingSubLevel(serverLevel, localA);

        final ServerSubLevel subLevelB =
                getContainingSubLevel(serverLevel, localB);

        if (subLevelA == null
                || subLevelB == null
                || subLevelA == subLevelB) {
            return false;
        }

        final Vec3 globalA =
                Sable.HELPER.projectOutOfSubLevel(serverLevel, localA);

        final Vec3 globalB =
                Sable.HELPER.projectOutOfSubLevel(serverLevel, localB);

        final Vector3d worldDirection =
                new Vector3d(
                        globalB.x - globalA.x,
                        globalB.y - globalA.y,
                        globalB.z - globalA.z
                );

        if (worldDirection.lengthSquared() < 0.000001D) {
            return false;
        }

        worldDirection.normalize();

        /*
         * Это ключевая часть.
         *
         * pos1 и pos2 должны описывать одну и ту же точку joint
         * в мировой системе, но в локальных координатах разных sublevel.
         *
         * Поэтому второй anchor переносится на уже существующую
         * дистанцию между фаркопами, а не на произвольные 2 блока.
         */
        final Vector3d worldAnchor =
                new Vector3d(globalA.x, globalA.y, globalA.z);

        final Vector3d localAnchorA =
                new Vector3d(localA.x, localA.y, localA.z);

        final Vector3d localAnchorB =
                subLevelB.logicalPose()
                        .transformPositionInverse(worldAnchor);

        /*
         * Ориентация продольной оси сцепки.
         * Оси X/Y/Z блокируются, чтобы joint не растягивался
         * и не сдвигался поперёк.
         *
         * ANGULAR_X/Y свободны, поэтому прицепы могут поворачиваться
         * влево/вправо и вверх/вниз.
         *
         * ANGULAR_Z заблокирована, чтобы вал не вращался вокруг себя.
         */
        final Vector3d localDirectionA =
                subLevelA.logicalPose()
                        .transformNormalInverse(worldDirection);

        final Vector3d localDirectionB =
                subLevelB.logicalPose()
                        .transformNormalInverse(
                                new Vector3d(worldDirection).negate()
                        );

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

        final GenericConstraintConfiguration configuration =
                new GenericConstraintConfiguration(
                        localAnchorA,
                        localAnchorB,
                        frameA,
                        frameB,
                        EnumSet.of(
                                ConstraintJointAxis.LINEAR_X,
                                ConstraintJointAxis.LINEAR_Y,
                                ConstraintJointAxis.LINEAR_Z,
                                ConstraintJointAxis.ANGULAR_Z
                        )
                );

        final SubLevelContainer container =
                SubLevelContainer.getContainer(serverLevel);

        if (!(container instanceof ServerSubLevelContainer serverContainer)) {
            return false;
        }

        final SubLevelPhysicsSystem physicsSystem =
                serverContainer.physicsSystem();

        final PhysicsPipeline pipeline =
                physicsSystem.getPipeline();

        removePhysicsConstraint();

        couplingConstraint =
                pipeline.addConstraint(
                        subLevelA,
                        subLevelB,
                        configuration
                );

        if (couplingConstraint == null) {
            return false;
        }

        couplingConstraint.setContactsEnabled(false);

        return couplingConstraint.isValid();
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
        if (couplingConstraint != null) {
            couplingConstraint.remove();
            couplingConstraint = null;
        }
    }

    public void detachCoupling() {
        if (level != null) {
            detachCoupling(level, worldPosition);
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

        final BlockPos targetPos = first.couplingTarget;

        first.clearCouplingData();
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

        second.clearCouplingData();
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

        if (level == null || level.isClientSide) {
            return;
        }

        final BlockState state = getBlockState();

        if (state.hasProperty(TowbarBlock.HOOKED)) {
            final boolean hooked = ropeHolder.isAttached();

            if (state.getValue(TowbarBlock.HOOKED) != hooked) {
                level.setBlock(
                        worldPosition,
                        state.setValue(
                                TowbarBlock.HOOKED,
                                hooked
                        ),
                        Block.UPDATE_CLIENTS
                );
            }
        }

        if (!couplingOwner || couplingTarget == null) {
            return;
        }

        if (couplingConstraint == null
                || !couplingConstraint.isValid()) {
            createPhysicsConstraint();
        }
    }

    @Override
    protected void write(
            final CompoundTag tag,
            final HolderLookup.Provider registries,
            final boolean clientPacket
    ) {
        super.write(tag, registries, clientPacket);

        if (couplingTarget != null) {
            tag.putLong(
                    "CouplingTarget",
                    couplingTarget.asLong()
            );
        }

        tag.putBoolean("CouplingOwner", couplingOwner);
        tag.putDouble("CouplingLength", couplingLength);
    }

    @Override
    protected void read(
            final CompoundTag tag,
            final HolderLookup.Provider registries,
            final boolean clientPacket
    ) {
        super.read(tag, registries, clientPacket);

        couplingTarget =
                tag.contains("CouplingTarget")
                        ? BlockPos.of(
                                tag.getLong("CouplingTarget")
                        )
                        : null;

        couplingOwner =
                tag.getBoolean("CouplingOwner");

        couplingLength =
                tag.contains("CouplingLength")
                        ? tag.getDouble("CouplingLength")
                        : 0.0D;
    }
}
