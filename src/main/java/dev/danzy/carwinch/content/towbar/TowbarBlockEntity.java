package dev.danzy.carwinch.content.towbar;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
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
 * <h2>Как это работает у настоящего прицепа</h2>
 *
 * У реальной сцепки есть <b>две</b> степени свободы по рысканью, а не одна:
 * дышло (водило) висит на шаре тягача и вторым концом закреплено на
 * поворотном узле прицепа. Поэтому при повороте события идут строго по
 * очереди:
 *
 * <ol>
 *   <li>тягач поворачивает — шар уходит в сторону;</li>
 *   <li>дышло <i>складывается</i> относительно тягача: сначала меняется
 *       угол сцепки, прицеп пока едет прямо;</li>
 *   <li>дышло тянет прицеп <i>вдоль себя</i>, и только тогда прицеп
 *       доворачивает в сторону поворота, с запаздыванием и по меньшему
 *       радиусу («срезает» траекторию).</li>
 * </ol>
 *
 * Ключевой момент: через дышло с двумя шарнирами передаётся практически
 * только продольная сила (тяга/сжатие). Оно почти не передаёт крутящий
 * момент, поэтому прицеп нельзя «повернуть рулём» — его можно только
 * тянуть.
 *
 * <h2>Как это сделано здесь</h2>
 *
 * Раньше вал был жёстко приварен ко второму фаркопу: якорь второго
 * sublevel считался один раз при клике. Из-за этого курс прицепа был
 * жёстко связан с направлением вала, прицеп доворачивал мгновенно вместе
 * с тягачом и на поворотах «выламывался» вбок — того самого запаздывания
 * не было.
 *
 * Теперь якорь второго тела пересчитывается каждый тик по фактическому
 * направлению вала ({@link #refreshCouplingFrames()}). Физически это и
 * есть дышло с шарнирами на обоих концах: точка сцепки всегда лежит на
 * линии между фаркопами, вал свободно складывается влево-вправо, а прицеп
 * получает тягу вдоль вала и доворачивает уже сам.
 *
 * Угловые оси больше не блокируются жёстко, вместо этого стоят упоры, как
 * у настоящего шара: рысканье до {@link #JACKKNIFE_LIMIT} (складывание
 * «в нож»), продольный перелом до {@link #PITCH_LIMIT}, крен относительно
 * тягача до {@link #ROLL_LIMIT}. Жёсткая блокировка ANGULAR_Z при больших
 * углах рысканья как раз и давала рывки.
 *
 * Длина вала замеряется в момент соединения и дальше не меняется
 * (диапазон от {@link #MIN_COUPLING_LENGTH} до
 * {@link #MAX_COUPLING_LENGTH}), но подтягивается к номиналу плавно, не
 * более {@link #MAX_LENGTH_CORRECTION_PER_TICK} блока за тик — без этого
 * любое расхождение выправлялось бы рывком.
 */
public class TowbarBlockEntity extends SmartBlockEntity
        implements RopeStrandHolderBlockEntity {

    /** Максимальная длина вала-сцепки в блоках. */
    public static final double MAX_COUPLING_LENGTH = 4.0D;

    /** Минимальная длина: ниже не определить направление вала. */
    public static final double MIN_COUPLING_LENGTH = 0.05D;

    /** Максимальный перепад по высоте: вал должен быть горизонтальным. */
    public static final double MAX_VERTICAL_OFFSET = 0.75D;

    /** Предел складывания «в нож» по рысканью. */
    private static final double JACKKNIFE_LIMIT = Math.toRadians(100.0D);

    /** Предел перелома вала вверх-вниз. */
    private static final double PITCH_LIMIT = Math.toRadians(25.0D);

    /** Допустимый крен прицепа относительно тягача. */
    private static final double ROLL_LIMIT = Math.toRadians(10.0D);

    /** Насколько сильно длина вала подтягивается к номиналу за один тик. */
    private static final double MAX_LENGTH_CORRECTION_PER_TICK = 0.15D;

    /** Пауза между попытками пересоздать констрейнт, в тиках. */
    private static final int CONSTRAINT_RETRY_INTERVAL = 20;

    /** Сколько раз пытаемся, прежде чем перестать дёргать физику каждый тик. */
    private static final int MAX_CONSTRAINT_ATTEMPTS = 10;

    private RopeStrandHolderBehavior ropeHolder;

    private BlockPos couplingTarget;
    private boolean couplingOwner;

    /** Замеренная в момент клика длина вала. */
    private double couplingLength;

    private GenericConstraintHandle couplingConstraint;

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
     * Геометрия joint в локальных системах обоих sublevel.
     *
     * @param anchorA точка сцепки на тягаче (его точка крепления)
     * @param anchorB та же точка в локальных координатах прицепа:
     *                его точка крепления, отодвинутая назад по
     *                фактическому направлению вала на его длину
     * @param frameA  ось вала в системе первого тела
     * @param frameB  ось вала в системе второго тела
     */
    private record JointFrames(
            Vector3d anchorA,
            Vector3d anchorB,
            Quaterniond frameA,
            Quaterniond frameB
    ) {
    }

    /**
     * Считает геометрию дышла по текущему положению обоих фаркопов.
     *
     * Именно здесь и живёт поведение настоящей сцепки: точка joint всегда
     * лежит на линии между фаркопами, то есть дышло свободно
     * переориентируется относительно прицепа и передаёт ему тягу вдоль
     * себя, а не разворачивает его насильно.
     *
     * @param smooth если true, длина подтягивается к номиналу постепенно;
     *               при создании констрейнта берётся точный номинал
     */
    @Nullable
    private JointFrames computeJointFrames(
            final ServerLevel serverLevel,
            final TowbarBlockEntity target,
            final boolean smooth
    ) {
        final Vec3 localA = getAttachmentPoint();
        final Vec3 localB = target.getAttachmentPoint();

        final ServerSubLevel subLevelA =
                getContainingSubLevel(serverLevel, localA);

        final ServerSubLevel subLevelB =
                getContainingSubLevel(serverLevel, localB);

        if (subLevelA == null
                || subLevelB == null
                || subLevelA == subLevelB) {
            return null;
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

        final double actualLength = worldDirection.length();

        if (actualLength < MIN_COUPLING_LENGTH) {
            return null;
        }

        worldDirection.div(actualLength);

        /*
         * Если длина потерялась (например, мир из старой версии),
         * замеряем её заново и запоминаем.
         */
        if (couplingLength < MIN_COUPLING_LENGTH) {
            couplingLength =
                    Math.min(actualLength, MAX_COUPLING_LENGTH);

            target.couplingLength = couplingLength;

            markUpdated();
            target.markUpdated();
        }

        /*
         * Плавное подтягивание к номиналу: рывками сцепку не выправляем,
         * иначе прицеп дёргает при каждом расхождении.
         */
        double effectiveLength = couplingLength;

        if (smooth) {
            final double delta = couplingLength - actualLength;

            final double clamped =
                    Math.max(
                            -MAX_LENGTH_CORRECTION_PER_TICK,
                            Math.min(
                                    MAX_LENGTH_CORRECTION_PER_TICK,
                                    delta
                            )
                    );

            effectiveLength = actualLength + clamped;
        }

        final Vector3d anchorA =
                new Vector3d(localA.x, localA.y, localA.z);

        /*
         * Точка второго тела, которая должна совпасть с точкой сцепки:
         * его точка крепления, отодвинутая назад по ФАКТИЧЕСКОМУ
         * направлению вала. Пересчёт каждый тик и есть второй шарнир.
         */
        final Vector3d worldAnchorB =
                new Vector3d(
                        globalB.x - worldDirection.x * effectiveLength,
                        globalB.y,
                        globalB.z - worldDirection.z * effectiveLength
                );

        final Vector3d anchorB =
                subLevelB.logicalPose()
                        .transformPositionInverse(worldAnchorB);

        /*
         * Продольная ось вала в локальных системах обоих sublevel.
         * Frame строится от +Z, как ожидает generic constraint:
         * Z — вдоль вала (крен), Y — вверх (рысканье), X — поперёк (перелом).
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

        return new JointFrames(anchorA, anchorB, frameA, frameB);
    }

    /**
     * Строит дышло: шаровой joint в точке сцепки.
     *
     * Заблокированы только линейные оси — это и есть шар. Углы держатся
     * не блокировкой, а упорами, как в настоящей сцепке.
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

        final JointFrames frames =
                computeJointFrames(serverLevel, target, false);

        if (frames == null) {
            return false;
        }

        final GenericConstraintConfiguration configuration =
                new GenericConstraintConfiguration(
                        frames.anchorA(),
                        frames.anchorB(),
                        frames.frameA(),
                        frames.frameB(),
                        EnumSet.of(
                                ConstraintJointAxis.LINEAR_X,
                                ConstraintJointAxis.LINEAR_Y,
                                ConstraintJointAxis.LINEAR_Z
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
                        subLevelOf(serverLevel, this),
                        subLevelOf(serverLevel, target),
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

        applyJointLimits();

        resetRetryState();
        return true;
    }

    @Nullable
    private static ServerSubLevel subLevelOf(
            final ServerLevel serverLevel,
            final TowbarBlockEntity towbar
    ) {
        return getContainingSubLevel(
                serverLevel,
                towbar.getAttachmentPoint()
        );
    }

    /**
     * Упоры шара вместо жёстких блокировок.
     *
     * Рысканье свободно почти до складывания «в нож», перелом вверх-вниз
     * ограничен, крен относительно тягача почти запрещён — но всё это
     * мягкие упоры, поэтому на больших углах поворота сцепку не рвёт
     * рывками, как при жёсткой блокировке ANGULAR_Z.
     */
    private void applyJointLimits() {
        if (couplingConstraint == null || !couplingConstraint.isValid()) {
            return;
        }

        try {
            couplingConstraint.setLimit(
                    ConstraintJointAxis.ANGULAR_Y,
                    -JACKKNIFE_LIMIT,
                    JACKKNIFE_LIMIT
            );

            couplingConstraint.setLimit(
                    ConstraintJointAxis.ANGULAR_X,
                    -PITCH_LIMIT,
                    PITCH_LIMIT
            );

            couplingConstraint.setLimit(
                    ConstraintJointAxis.ANGULAR_Z,
                    -ROLL_LIMIT,
                    ROLL_LIMIT
            );
        } catch (final RuntimeException ignored) {
            /*
             * Если сборка физики не поддерживает упоры на свободных осях,
             * сцепка всё равно работает как шар — просто без ограничителей.
             */
        }
    }

    /**
     * Пересчитывает якоря дышла по фактическому положению фаркопов.
     *
     * Это и есть второй шарнир: вал каждый тик заново «нацеливается» на
     * шар тягача, поэтому при повороте сначала складывается сцепка, а
     * прицеп доворачивает только под действием тяги вдоль вала.
     */
    private void refreshCouplingFrames() {
        if (couplingConstraint == null
                || !couplingConstraint.isValid()
                || couplingTarget == null
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        final TowbarBlockEntity target =
                getTowbar(serverLevel, couplingTarget);

        if (target == null) {
            return;
        }

        final JointFrames frames =
                computeJointFrames(serverLevel, target, true);

        if (frames == null) {
            return;
        }

        try {
            couplingConstraint.setFrame1(
                    frames.anchorA(),
                    frames.frameA()
            );

            couplingConstraint.setFrame2(
                    frames.anchorB(),
                    frames.frameB()
            );
        } catch (final RuntimeException ignored) {
            /*
             * Констрейнт мог быть снят движком между проверкой и вызовом;
             * в следующем тике он пересоздастся.
             */
        }
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

            // Дышло «нацеливается» на шар тягача каждый тик.
            refreshCouplingFrames();
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
