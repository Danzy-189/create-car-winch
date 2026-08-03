package dev.danzy.carwinch.content.winch;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.danzy.carwinch.content.rope.CarWinchRopeHelper;
import dev.danzy.carwinch.registry.CWItems;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachment;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachmentPoint;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
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

    /** Сколько тиков ищем чужой дроп после того, как трос пропал. */
    private static final int STRAY_DROP_SCAN_TICKS = 4;
    /** Максимальный возраст дропа, который ещё считается нашим. */
    private static final int STRAY_DROP_MAX_AGE = 6;
    /** Размер куба поиска вокруг каждой известной точки троса. */
    private static final double STRAY_DROP_SEARCH_SIZE = 8.0;

    private RopeStrandHolderBehavior ropeHolder;

    /** Purely cosmetic drum spin, driven client side. */
    public final LerpedFloat drumAngle = LerpedFloat.angular();
    private float clientDrumSpeed;

    /**
     * Сила и направление работы лебёдки, посчитанные на сервере
     * и синхронизированные на клиент.
     *
     * Раньше клиент сам вызывал level.getSignal(), но на клиенте
     * сила редстоуна достоверна не всегда, из-за чего анимация
     * барабана расходилась с реальной работой лебёдки.
     */
    private int commandedPower;

    /** Был ли трос в прошлом тике. */
    private boolean hadRope;
    /** Дальний конец троса: туда Simulated роняет верёвку при сломе блока. */
    @Nullable
    private BlockPos lastRopeEnd;
    /** Середина троса: туда Simulated роняет верёвку в остальных случаях. */
    @Nullable
    private Vec3 lastRopeMiddle;
    /** Остаток окна поиска чужого дропа. */
    private int strayDropScanTicks;

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

    /** Максимальный "тянущий" сигнал: сверху или с любой боковой стороны. */
    private static int pullSignal(final Level level, final BlockPos pos) {
        int pull = signalFrom(level, pos, Direction.UP);

        for (final Direction side : Direction.Plane.HORIZONTAL) {
            pull = Math.max(pull, signalFrom(level, pos, side));
        }

        return pull;
    }

    /** "Травящий" сигнал: только снизу. */
    private static int releaseSignal(final Level level, final BlockPos pos) {
        return signalFrom(level, pos, Direction.DOWN);
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
        return pullSignal(level, pos) - releaseSignal(level, pos);
    }

    /** true, если сигналы гасят друг друга - трос заблокирован. */
    private static boolean isBraked(final Level level, final BlockPos pos) {
        final int pull = pullSignal(level, pos);
        final int release = releaseSignal(level, pos);

        return pull == release && pull > 0;
    }

    /** Синхронизированная сила: положительная тянет, отрицательная травит. */
    public int getCommandedPower() {
        return this.commandedPower;
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
            this.clientDrumSpeed = DRUM_SPIN_PER_TICK * (this.commandedPower / 15.0F);
            return;
        }

        final int commanded = getCommandedPower(this.level, this.worldPosition);

        if (commanded != this.commandedPower) {
            this.commandedPower = commanded;
            this.notifyUpdate();
        }

        this.trackRopeForStrayDrops();
        this.syncRopedState();

        final ServerRopeStrand strand = this.ropeHolder.getOwnedStrand();
        if (strand != null && this.ropeHolder.ownsRope()) {
            this.updateRopeStrandExtension(strand, commanded);
        }
    }

    /**
     * Страховка от чужой верёвки.
     *
     * Обычные случаи (ножницы и слом блока) перехватываются заранее в
     * RopeInterceptEvents. Остаются редкие пути внутри Simulated: взрыв,
     * исчезновение точки крепления, разборка конструкции. Там трос рвётся
     * без нашего ведома и на землю падает верёвка Simulated - подменяем
     * её на стальной трос в течение нескольких тиков после пропажи троса.
     */
    private void trackRopeForStrayDrops() {
        final boolean hasRope = this.ropeHolder.isAttached();

        if (hasRope) {
            this.rememberRopeShape();
        } else if (this.hadRope) {
            this.strayDropScanTicks = STRAY_DROP_SCAN_TICKS;
        }

        this.hadRope = hasRope;

        if (this.strayDropScanTicks > 0) {
            this.strayDropScanTicks--;
            this.convertStrayRopeDrops();
        }
    }

    /** Запоминает, где искать дроп, если трос вдруг исчезнет. */
    private void rememberRopeShape() {
        final ServerRopeStrand strand = this.ropeHolder.getOwnedStrand();
        if (strand == null) {
            return;
        }

        final RopeAttachment end = strand.getAttachment(RopeAttachmentPoint.END);
        this.lastRopeEnd = end != null ? end.blockAttachment() : null;

        final List<Vector3d> points = strand.getPoints();
        if (!points.isEmpty()) {
            final Vector3d middle = points.get(points.size() / 2);
            this.lastRopeMiddle = new Vec3(middle.x, middle.y, middle.z);
        }
    }

    private void convertStrayRopeDrops() {
        if (!this.level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            return;
        }

        this.convertStrayRopeDropsAt(this.worldPosition.getCenter());

        if (this.lastRopeEnd != null) {
            this.convertStrayRopeDropsAt(this.lastRopeEnd.getCenter());
        }

        if (this.lastRopeMiddle != null) {
            this.convertStrayRopeDropsAt(this.lastRopeMiddle);
        }
    }

    private void convertStrayRopeDropsAt(final Vec3 center) {
        final AABB area = AABB.ofSize(
                center,
                STRAY_DROP_SEARCH_SIZE,
                STRAY_DROP_SEARCH_SIZE,
                STRAY_DROP_SEARCH_SIZE
        );

        for (final ItemEntity itemEntity : this.level.getEntitiesOfClass(ItemEntity.class, area)) {
            if (itemEntity.tickCount > STRAY_DROP_MAX_AGE) {
                continue;
            }

            final ItemStack stack = itemEntity.getItem();
            if (!CarWinchRopeHelper.isPlainRope(stack)) {
                continue;
            }

            itemEntity.setItem(new ItemStack(CWItems.IRON_ROPE.get(), stack.getCount()));
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
     * Сверху - наматывает, снизу - разматывает.
     * Без сигнала лебёдка на свободном ходу и травит трос, когда он натянулся,
     * чтобы буксируемое всё ещё могло уехать.
     */
    private void updateRopeStrandExtension(final ServerRopeStrand strand, final int commanded) {
        final double desiredExtension = strand.getExtension()
                + (strand.getPoints().size() - 2) * ServerRopeStrand.SEGMENT_LENGTH;
        final double currentExtension = strand.getCurrentExtension();

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
    protected void write(final CompoundTag tag, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("CommandedPower", this.commandedPower);
    }

    @Override
    protected void read(final CompoundTag tag, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.commandedPower = tag.getInt("CommandedPower");
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
