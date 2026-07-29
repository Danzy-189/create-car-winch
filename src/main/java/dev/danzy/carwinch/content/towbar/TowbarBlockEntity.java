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
 * <h2>Как устроена настоящая сцепка</h2>
 *
 * У реального прицепа дышло <b>жёстко закреплено на самом прицепе</b> вдоль
 * его продольной оси, а единственный шарнир — это шар на тягаче. Из этого
 * сама собой вытекает вся кинематика поворота:
 *
 * <ol>
 *   <li>тягач поворачивает — шар уходит в сторону;</li>
 *   <li>первым меняется угол на шаре: сцепка складывается, прицеп пока
 *       едет прямо;</li>
 *   <li>дышло тянет прицеп вдоль себя, и прицеп доворачивает уже с
 *       запаздыванием, срезая траекторию.</li>
 * </ol>
 *
 * <h2>Как это сделано здесь</h2>
 *
 * <b>Первый клик — сторона шара</b> (тягач), второй — сторона дышла
 * (прицеп). Владелец сцепки и есть шар.
 *
 * Ключевое: и оси joint, и якорь со стороны прицепа берутся из
 * <b>собственных поворотов блоков</b> ({@link TowbarBlock#FACING}), а не из
 * замеренной геометрии в момент клика. В своей системе координат каждого
 * sublevel они <b>постоянны</b>:
 *
 * <ul>
 *   <li>якорь на тягаче — его точка крепления;</li>
 *   <li>якорь на прицепе — его точка крепления, вынесенная вперёд по
 *       собственному facing на длину вала: это и есть жёсткое дышло
 *       прицепа;</li>
 *   <li>оси joint направлены по facing каждого из фаркопов.</li>
 * </ul>
 *
 * За счёт этого вал выравнивается по прицепу независимо от того, как его
 * скрепили, и констрейнт не надо пересоздавать при движении.
 *
 * <h2>Разная высота фаркопов</h2>
 *
 * Линейные оси заблокированы жёстко, то есть решатель обязан свести оба
 * якоря в одну точку. Если фаркопы стоят на разной высоте, а якорь дышла
 * вынесен строго горизонтально, то в joint остаётся невязка по вертикали —
 * и убрать её решатель может только одним способом: дёрнуть тела по
 * вертикали, отрывая колёса от земли.
 *
 * Поэтому перепад высот между точками крепления замеряется при сцеплении
 * ({@link #couplingHeightOffset}) и закладывается в якорь дышла, а не
 * лечится силой. Дышло просто стоит под наклоном, и в покое joint вообще
 * не напряжён.
 *
 * <h2>Почему все угловые оси свободны</h2>
 *
 * Сцепка — чистый шарнир: заблокированы только три линейные оси,
 * угловые свободны все три и без упоров, то есть вал крутится вокруг
 * своей оси на все 360 градусов.
 *
 * Любая связь по углам — будь то жёсткая блокировка крена или упор по
 * наклону — связывает ориентацию двух sublevel между собой. Тогда наклон
 * одного тела на рельефе насильно наклоняет и второе — его колёса
 * отрываются от земли, а на повороте под уклон возникает момент, который
 * переворачивает технику. Поэтому каждый едет по своему рельефу сам, а
 * сцепка держит только точку. Бонусом generic-joint решатель без упоров
 * считается заметно стабильнее.
 *
 * Длина вала замеряется при соединении и дальше постоянна (диапазон от
 * {@link #MIN_COUPLING_LENGTH} до {@link #MAX_COUPLING_LENGTH}).
 *
 * Страховка от разлёта: если геометрия стала невозможной
 * ({@link #SANITY_SLACK}), сцепка рвётся, а не пытается стянуть
 * разошедшиеся тела обратно.
 */
public class TowbarBlockEntity extends SmartBlockEntity
        implements RopeStrandHolderBlockEntity {

    /** Максимальная длина вала-сцепки в блоках. */
    public static final double MAX_COUPLING_LENGTH = 4.0D;

    /** Минимальная длина: ниже не определить направление вала. */
    public static final double MIN_COUPLING_LENGTH = 0.05D;

    /**
     * Максимальный перепад высот между точками крепления, при котором
     * сцепка ещё разрешена. Сам перепад не мешает: он закладывается в
     * якорь дышла, поэтому запас можно держать щедрым.
     */
    public static final double MAX_VERTICAL_OFFSET = 1.5D;

    /**
     * Запас к допустимой геометрии, после которого считаем, что физику
     * разошлось, и рвём сцепку вместо попыток стянуть тела обратно.
     */
    private static final double SANITY_SLACK = 2.0D;

    /** Пауза между попытками пересоздать констрейнт, в тиках. */
    private static final int CONSTRAINT_RETRY_INTERVAL = 20;

    /** Сколько раз пытаемся, прежде чем перестать дёргать физику каждый тик. */
    private static final int MAX_CONSTRAINT_ATTEMPTS = 10;

    /**
     * Степени свободы сцепки: держим только точку, все три поворота
     * свободны. Именно это даёт вращение вала вокруг своей оси на 360
     * градусов и не позволяет одной единице техники наклонять или
     * переворачивать другую.
     */
    private static final EnumSet<ConstraintJointAxis> LOCKED_AXES =
            EnumSet.of(
                    ConstraintJointAxis.LINEAR_X,
                    ConstraintJointAxis.LINEAR_Y,
                    ConstraintJointAxis.LINEAR_Z
            );

    private RopeStrandHolderBehavior ropeHolder;

    private BlockPos couplingTarget;
    private boolean couplingOwner;

    /** Замеренная в момент клика длина вала. */
    private double couplingLength;

    /**
     * Замеренный в момент клика перепад высот: высота точки крепления
     * прицепа минус высота точки крепления тягача. Закладывается в якорь
     * дышла, чтобы разная высота фаркопов не превращалась в вертикальную
     * невязку joint.
     */
    private double couplingHeightOffset;

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

    /** Собственный поворот фаркопа: он же задаёт ось сцепки. */
    private Direction getFacing() {
        return getBlockState().getValue(TowbarBlock.FACING);
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
     * Горизонтальная проекция расстояния: длина вала считается по
     * горизонтали, а перепад высот учитывается отдельно.
     */
    private static double horizontalLength(final Vec3 delta) {
        return Math.sqrt(delta.x * delta.x + delta.z * delta.z);
    }

    private static boolean isFinite(final Vec3 vec) {
        return Double.isFinite(vec.x)
                && Double.isFinite(vec.y)
                && Double.isFinite(vec.z);
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

    /**
     * Соединяет два фаркопа.
     *
     * @param first  фаркоп, по которому кликнули первым: это сторона
     *               <b>шара</b>, то есть тягач
     * @param second второй фаркоп: сторона дышла, то есть прицеп
     */
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

        /*
         * Оси joint строятся по facing блоков, поэтому вертикально
         * поставленный фаркоп сцепкой быть не может: у вала не будет
         * горизонтальной оси.
         */
        if (!first.getFacing().getAxis().isHorizontal()
                || !second.getFacing().getAxis().isHorizontal()) {
            return CouplingResult.NOT_HORIZONTAL;
        }

        final Vec3 firstLocal = first.getAttachmentPoint();
        final Vec3 secondLocal = second.getAttachmentPoint();

        final Vec3 firstGlobal =
                Sable.HELPER.projectOutOfSubLevel(level, firstLocal);

        final Vec3 secondGlobal =
                Sable.HELPER.projectOutOfSubLevel(level, secondLocal);

        final Vec3 globalDelta = secondGlobal.subtract(firstGlobal);

        if (!isFinite(globalDelta)) {
            return CouplingResult.PHYSICS_FAILED;
        }

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
        first.couplingHeightOffset = globalDelta.y;
        first.resetRetryState();

        second.couplingTarget = first.worldPosition;
        second.couplingOwner = false;
        second.couplingLength = length;
        second.couplingHeightOffset = globalDelta.y;
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
        couplingHeightOffset = 0.0D;
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
     * Ось вала в системе координат блока. Frame строится от +Z, как ждёт
     * generic constraint: Z — вдоль вала, Y — вверх, X — поперёк.
     */
    private static Quaterniond frameFor(final Direction facing) {
        final Vector3d axis = new Vector3d(
                facing.getStepX(),
                facing.getStepY(),
                facing.getStepZ()
        );

        return new Quaterniond().rotationTo(
                new Vector3d(0.0D, 0.0D, 1.0D),
                axis
        );
    }

    /**
     * Строит сцепку: шар на тягаче плюс жёсткое дышло прицепа.
     *
     * Якоря задаются координатами плота, как ждёт
     * {@link GenericConstraintConfiguration} (нативная сторона сама
     * переводит их в локальные). Оба якоря постоянны в системе своего
     * sublevel, поэтому joint не нужно пересоздавать при движении.
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

        final Direction facingA = getFacing();
        final Direction facingB = target.getFacing();

        if (!facingA.getAxis().isHorizontal()
                || !facingB.getAxis().isHorizontal()) {
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

        /*
         * Если длина потерялась (например, мир из старой версии),
         * замеряем её заново вместе с перепадом высот по фактическому
         * положению фаркопов.
         */
        if (couplingLength < MIN_COUPLING_LENGTH) {
            final Vec3 globalA =
                    Sable.HELPER.projectOutOfSubLevel(serverLevel, localA);

            final Vec3 globalB =
                    Sable.HELPER.projectOutOfSubLevel(serverLevel, localB);

            final Vec3 delta = globalB.subtract(globalA);

            if (!isFinite(delta)) {
                return false;
            }

            final double measured = horizontalLength(delta);

            if (measured < MIN_COUPLING_LENGTH) {
                return false;
            }

            couplingLength =
                    Math.min(measured, MAX_COUPLING_LENGTH);

            couplingHeightOffset = Math.max(
                    -MAX_VERTICAL_OFFSET,
                    Math.min(MAX_VERTICAL_OFFSET, delta.y)
            );

            target.couplingLength = couplingLength;
            target.couplingHeightOffset = couplingHeightOffset;

            markUpdated();
            target.markUpdated();
        }

        // Шар: собственная точка крепления тягача.
        final Vector3d anchorA =
                new Vector3d(localA.x, localA.y, localA.z);

        /*
         * Дышло: точка крепления прицепа, вынесенная вперёд по его
         * СОБСТВЕННОМУ facing на длину вала и опущенная/поднятая на
         * замеренный перепад высот: тогда в покое якоря совпадают сами
         * собой и joint не тянет тела по вертикали.
         */
        final Vector3d anchorB =
                new Vector3d(
                        localB.x + facingB.getStepX() * couplingLength,
                        localB.y - couplingHeightOffset,
                        localB.z + facingB.getStepZ() * couplingLength
                );

        final GenericConstraintConfiguration configuration =
                new GenericConstraintConfiguration(
                        anchorA,
                        anchorB,
                        frameFor(facingA),
                        frameFor(facingB),
                        EnumSet.copyOf(LOCKED_AXES)
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

        try {
            couplingConstraint =
                    pipeline.addConstraint(
                            subLevelA,
                            subLevelB,
                            configuration
                    );
        } catch (final RuntimeException ignored) {
            /*
             * addConstraint валидирует якоря и швыряет исключение, если
             * точка вышла за плот sublevel или тело уже удалено. Считаем
             * попытку неудачной и пробуем позже.
             */
            couplingConstraint = null;
            return false;
        }

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

    /**
     * Страховка от разлёта: если геометрия стала невозможной, рвём сцепку.
     * Пытаться стянуть разошедшиеся тела обратно — это как раз тот случай,
     * когда joint выдаёт импульс, уносящий sublevel по всему миру.
     *
     * Вертикаль сравнивается с заложенным перепадом высот, а не с нулём:
     * наклонённое дышло — это норма, а не признак разлёта.
     */
    private boolean isCouplingSane(final ServerLevel serverLevel) {
        final TowbarBlockEntity target =
                getTowbar(serverLevel, couplingTarget);

        if (target == null) {
            return true;
        }

        final Vec3 globalA = Sable.HELPER.projectOutOfSubLevel(
                serverLevel,
                getAttachmentPoint()
        );

        final Vec3 globalB = Sable.HELPER.projectOutOfSubLevel(
                serverLevel,
                target.getAttachmentPoint()
        );

        final Vec3 delta = globalB.subtract(globalA);

        if (!isFinite(delta)) {
            return false;
        }

        return horizontalLength(delta)
                        <= MAX_COUPLING_LENGTH + SANITY_SLACK
                && Math.abs(delta.y - couplingHeightOffset)
                        <= MAX_VERTICAL_OFFSET + SANITY_SLACK;
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
        if (couplingConstraint == null) {
            return;
        }

        try {
            couplingConstraint.remove();
        } catch (final RuntimeException ignored) {
            // Констрейнт мог быть уже снят движком.
        }

        couplingConstraint = null;
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
     * сохраняются, а освобождается только хэндл констрейнта: иначе он
     * остаётся висеть в физическом пайплайне. При загрузке констрейнт
     * пересоздаётся в tick().
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

        if (!couplingOwner
                || couplingTarget == null
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (couplingConstraint != null && couplingConstraint.isValid()) {
            constraintAttempts = 0;

            if (!isCouplingSane(serverLevel)) {
                detachCoupling();
            }

            return;
        }

        /*
         * Между попытками пересоздания держим паузу: если констрейнт в
         * принципе невозможен, физику не стоит дёргать 20 раз в секунду.
         * После лимита попыток сцепка просто расходится.
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
        tag.putDouble("CouplingHeight", couplingHeightOffset);
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

        couplingHeightOffset = Math.max(
                -MAX_VERTICAL_OFFSET,
                Math.min(
                        MAX_VERTICAL_OFFSET,
                        tag.getDouble("CouplingHeight")
                )
        );
    }
}
