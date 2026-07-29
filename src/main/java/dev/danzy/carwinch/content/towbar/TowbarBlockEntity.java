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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.EnumSet;
import java.util.List;

/**
 * Фаркоп.
 *
 * Два фаркопа соединяются жёстким горизонтальным валом Create.
 *
 * Длина вала замеряется в момент соединения и дальше не меняется:
 * сцепка жёсткая, физика удерживает именно это расстояние. Допустимый
 * диапазон — от {@link #MIN_COUPLING_LENGTH} до {@link #MAX_COUPLING_LENGTH},
 * так что фаркопы можно ставить и вплотную, и в четырёх блоках
 * друг от друга. Телепорта sublevel нет: номинал равен фактическому
 * расстоянию на момент клика.
 *
 * Вал горизонтальный: перепад по высоте между точками крепления
 * не должен превышать {@link #MAX_VERTICAL_OFFSET}.
 *
 * Физически это generic-constraint Sable между двумя sublevel:
 * LINEAR_X/Y/Z и ANGULAR_Z заблокированы, ANGULAR_X/Y свободны, поэтому
 * прицеп поворачивает влево-вправо и складывается вверх-вниз, но не
 * прокручивается вокруг продольной оси вала.
 */
public class TowbarBlockEntity extends SmartBlockEntity
        implements RopeStrandHolderBlockEntity {

    /** Максимальная длина вала-сцепки в блоках. */
    public static final double MAX_COUPLING_LENGTH = 4.0D;

    /** Минимальная длина: ниже не определить направление вала. */
    public static final double MIN_COUPLING_LENGTH = 0.05D;

    /** Максимальный перепад по высоте: вал должен быть горизонтальным. */
    public static final double MAX_VERTICAL_OFFSET = 0.75D;

    /** Пауза между попытками пересоздать констрейнт, в тиках. */
    private static final int CONSTRAINT_RETRY_INTERVAL = 20;

    /** Сколько раз пытаемся, прежде чем перестать дёргать физику каждый тик. */
    private static final int MAX_CONSTRAINT_ATTEMPTS = 10;

    private RopeStrandHolderBehavior ropeHolder;

    private BlockPos couplingTarget;
    private boolean couplingOwner;

    /** Замеренная в момент клика длина вала. */
    private double couplingLength;

    private PhysicsConstraintHandle couplingConstraint;

    private int constraintAttempts;
    private int retryCooldown;

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

    @Nullable
    public BlockPos getCouplingTarget() {
        return couplingTarget;
    }

    /**
     * Длина вала, зафиксированная при соединении.
     */
    public double getCouplingLength() {
        return couplingLength;
    }

    /**
     * Горизонтальная проекция расстояния: вал горизонтальный,
     * поэтому вертикальную составляющую в длину не считаем.
     */
    private static double horizontalLength(final Vec3 delta) {
        return Math.sqrt(delta.x * delta.x + delta.z * delta.z);
    }

    /**
     * Результат попытки соединения. Каждый вариант несёт ключ локализации,
     * чтобы игрок видел причину отказа, а не общее "не удалось".
     */
    public enum CouplingResult {

        SUCCESS(null),
        SAME_TOWBAR("carwinch.coupling.failed.same"),
        ALREADY_COUPLED("carwinch.coupling.occupied"),
        NOT_HORIZONTAL("carwinch.coupling.failed.horizontal"),
        TOO_FAR("carwinch.coupling.failed.length"),
        TOO_CLOSE("carwinch.coupling.failed.too_close"),
        NOT_ON_CONTRAPTION("carwinch.coupling.failed.contraption"),
        SAME_CONTRAPTION("carwinch.coupling.failed.same_contraption"),
        PHYSICS_FAILED("carwinch.coupling.failed.physics");

        private final String translationKey;

        CouplingResult(final String translationKey) {
            this.translationKey = translationKey;
        }

        public boolean isSuccess() {
            return this == SUCCESS;
        }

        @Nullable
        public String translationKey() {
            return translationKey;
        }
    }

    public static CouplingResult createCoupling(
            final TowbarBlockEntity first,
            final TowbarBlockEntity second
    ) {
        if (first == second
                || first.level == null
                || second.level == null
                || first.level != second.level) {
            return CouplingResult.SAME_TOWBAR;
        }

        if (first.isCoupled() || second.isCoupled()) {
            return CouplingResult.ALREADY_COUPLED;
        }

        if (!(first.level instanceof ServerLevel level)) {
            return CouplingResult.PHYSICS_FAILED;
        }

        final Vec3 firstLocal = first.getAttachmentPoint();
        final Vec3 secondLocal = second.getAttachmentPoint();

        final Vec3 firstGlobal =
                Sable.HELPER.projectOutOfSubLevel(level, firstLocal);

        final Vec3 secondGlobal =
                Sable.HELPER.projectOutOfSubLevel(level, secondLocal);

        final Vec3 globalDelta = secondGlobal.subtract(firstGlobal);

        final double length = horizontalLength(globalDelta);

        if (Math.abs(globalDelta.y) > MAX_VERTICAL_OFFSET) {
            return CouplingResult.NOT_HORIZONTAL;
        }

        if (length < MIN_COUPLING_LENGTH) {
            return CouplingResult.TOO_CLOSE;
        }

        if (length > MAX_COUPLING_LENGTH) {
            return CouplingResult.TOO_FAR;
        }

        final ServerSubLevel firstSubLevel =
                getContainingSubLevel(level, firstLocal);

        final ServerSubLevel secondSubLevel =
                getContainingSubLevel(level, secondLocal);

        if (firstSubLevel == null || secondSubLevel == null) {
            return CouplingResult.NOT_ON_CONTRAPTION;
        }

        if (firstSubLevel == secondSubLevel) {
            return CouplingResult.SAME_CONTRAPTION;
        }

        first.couplingTarget = second.worldPosition;
        first.couplingOwner = true;
        first.couplingLength = length;
        first.resetRetryState();

        second.couplingTarget = first.worldPosition;
        second.couplingOwner = false;
        second.couplingLength = length;
        second.resetRetryState();

        first.markUpdated();
        second.markUpdated();

        if (first.createPhysicsConstraint()) {
            return CouplingResult.SUCCESS;
        }

        first.clearCouplingData();
        second.clearCouplingData();

        first.markUpdated();
        second.markUpdated();

        return CouplingResult.PHYSICS_FAILED;
    }

    @Nullable
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

    private void resetRetryState() {
        constraintAttempts = 0;
        retryCooldown = 0;
    }

    private void clearCouplingData() {
        removePhysicsConstraint();
        couplingTarget = null;
        couplingOwner = false;
        couplingLength = 0.0D;
        resetRetryState();
    }

    private void markUpdated() {
        setChanged();

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

    /**
     * Строит физический вал зафиксированной длины.
     *
     * Оба якоря описывают одну и ту же точку joint в мировой системе,
     * но в локальных координатах разных sublevel. Якорь второго фаркопа
     * сдвинут назад по оси вала ровно на запомненную длину, поэтому
     * сцепка жёсткая и не растягивается со временем.
     */
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

        // Ось вала строго горизонтальная.
        final Vector3d worldDirection =
                new Vector3d(
                        globalB.x - globalA.x,
                        0.0D,
                        globalB.z - globalA.z
                );

        if (worldDirection.lengthSquared()
                < MIN_COUPLING_LENGTH * MIN_COUPLING_LENGTH) {
            return false;
        }

        worldDirection.normalize();

        /*
         * Если длина потерялась (например, мир из старой версии),
         * замеряем её заново и запоминаем.
         */
        if (couplingLength < MIN_COUPLING_LENGTH) {
            couplingLength = Math.min(
                    horizontalLength(globalB.subtract(globalA)),
                    MAX_COUPLING_LENGTH
            );

            target.couplingLength = couplingLength;

            markUpdated();
            target.markUpdated();
        }

        final Vector3d localAnchorA =
                new Vector3d(localA.x, localA.y, localA.z);

        /*
         * Точка второго тела, которая должна совпасть с якорем первого:
         * его точка крепления, отодвинутая назад по оси вала на
         * запомненную длину. Именно это и держит дистанцию постоянной.
         */
        final Vector3d worldAnchorB =
                new Vector3d(
                        globalB.x - worldDirection.x * couplingLength,
                        globalB.y,
                        globalB.z - worldDirection.z * couplingLength
                );

        final Vector3d localAnchorB =
                subLevelB.logicalPose()
                        .transformPositionInverse(worldAnchorB);

        /*
         * Продольная ось вала в локальных системах обоих sublevel.
         * Frame строится от +Z, как ожидает generic constraint.
         */
        final Vector3d localDirectionA =
                subLevelA.logicalPose()
                        .transformNormalInverse(
                                new Vector3d(worldDirection)
                        );

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

        if (!couplingConstraint.isValid()) {
            removePhysicsConstraint();
            return false;
        }

        resetRetryState();
        return true;
    }

    @Nullable
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
        first.markUpdated();

        if (targetPos == null) {
            return;
        }

        final TowbarBlockEntity second =
                getTowbar(level, targetPos);

        if (second == null) {
            return;
        }

        second.clearCouplingData();
        second.markUpdated();
    }

    /**
     * Хук Create: вызывается из финального SmartBlockEntity#setRemoved,
     * поэтому переопределять setRemoved нельзя и не нужно.
     * detachCoupling внутри снимает и физический констрейнт.
     */
    @Override
    public void remove() {
        detachCoupling();
        super.remove();
    }

    /**
     * При выгрузке чанка блок никуда не делся, поэтому данные сцепки
     * сохраняются, а освобождается только хэндл констрейнта:
     * иначе он остаётся висеть в физическом пайплайне. При загрузке
     * констрейнт пересоздаётся в tick().
     */
    @Override
    public void onChunkUnloaded() {
        removePhysicsConstraint();
        super.onChunkUnloaded();
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

        if (couplingConstraint != null && couplingConstraint.isValid()) {
            constraintAttempts = 0;
            return;
        }

        /*
         * Раньше здесь была попытка пересоздания каждый тик: если
         * констрейнт в принципе невозможен, физику дёргало 20 раз в секунду.
         * Теперь между попытками пауза, а после лимита попыток
         * сцепка просто расходится.
         */
        if (retryCooldown > 0) {
            retryCooldown--;
            return;
        }

        if (constraintAttempts >= MAX_CONSTRAINT_ATTEMPTS) {
            detachCoupling();
            return;
        }

        constraintAttempts++;
        retryCooldown = CONSTRAINT_RETRY_INTERVAL;

        createPhysicsConstraint();
    }

    /**
     * Вал уходит за пределы блока, поэтому расширяем bounding box,
     * иначе сцепку отрезает фрустумом при взгляде вдоль неё.
     */
    @Override
    public AABB getRenderBoundingBox() {
        if (!isCoupled()) {
            return super.getRenderBoundingBox();
        }

        return new AABB(worldPosition)
                .inflate(MAX_COUPLING_LENGTH + 1.0D);
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
                Math.min(
                        tag.getDouble("CouplingLength"),
                        MAX_COUPLING_LENGTH
                );
    }
}
