package dev.danzy.carwinch.content.winch;
import dev.danzy.carwinch.registry.CWSounds;
import net.minecraft.sounds.SoundSource;
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

public class CarWinchBlockEntity extends SmartBlockEntity
        implements RopeStrandHolderBlockEntity {

    public static final double MAX_RANGE = 10.0D;
    public static final float REEL_SPEED = 0.075F;
    public static final float PAYOUT_SPEED = 0.16F;
    public static final double SLACK_TOLERANCE = 1.03;
    public static final double RENDER_BOUNDING_BOX_INFLATION = 8.0;

    private RopeStrandHolderBehavior ropeHolder;

    public final LerpedFloat drumAngle = LerpedFloat.angular();
    private float clientDrumSpeed;
    private boolean lastPowered;

    public CarWinchBlockEntity(
            final BlockEntityType type,
            final BlockPos pos,
            final BlockState state
    ) {
        super(type, pos, state);
        this.setLazyTickRate(20);
    }

    @Override
    public void addBehaviours(final List behaviours) {
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
    public Vec3 getAttachmentPoint(
            final BlockPos pos,
            final BlockState state
    ) {
        final Direction facing = state.getValue(CarWinchBlock.FACING);

        return pos.getCenter().add(
                Vec3.atLowerCornerOf(facing.getNormal()).scale(0.44)
        );
    }

@Override
public void tick() {
    super.tick();

    if (this.level == null) {
        return;
    }

    /*
     * Клиент:
     * обновляем визуальное вращение барабана.
     */
    if (this.level.isClientSide) {
        this.invalidateRenderBoundingBox();

        this.clientDrumSpeed =
                this.getBlockState()
                        .getValue(CarWinchBlock.POWERED)
                        ? 14.0F
                        : 0.0F;

        this.drumAngle.setValue(
                this.drumAngle.getValue()
                        + this.clientDrumSpeed
        );

        return;
    }

    /*
     * Сервер:
     * синхронизируем состояние наличия троса.
     */
    this.syncRopedState();

    /*
     * Получаем текущую силу редстоун-сигнала.
     * Значения находятся в диапазоне от 0 до 15.
     */
    final int power =
            this.level.getBestNeighborSignal(
                    this.worldPosition
            );

    final boolean powered = power > 0;

    /*
     * Звук проигрывается только в момент перехода:
     *
     * powered = false
     * powered = true
     *
     * Поэтому звук не будет проигрываться каждый тик.
     */
    if (powered && !this.lastPowered) {
        this.level.playSound(
                null,
                this.worldPosition,
                CWSounds.WINCH_SOUND.get(),
                SoundSource.BLOCKS,
                1.0F,
                1.0F
        );
    }

    /*
     * Сохраняем текущее состояние для следующего тика.
     */
    this.lastPowered = powered;

    /*
     * Получаем физический трос, которым владеет лебёдка.
     */
    final ServerRopeStrand strand =
            this.ropeHolder.getOwnedStrand();

    if (strand != null
            && this.ropeHolder.ownsRope()) {
        this.updateRopeStrandExtension(
                strand,
                power
        );
    }
}
        /** POWERED теперь ведётся живым сигналом каждый тик, а не только по neighborChanged. */
private void syncPoweredState(final boolean powered) {
    final BlockState state = this.getBlockState();
    if (state.hasProperty(CarWinchBlock.POWERED) && state.getValue(CarWinchBlock.POWERED) != powered) {
        this.level.setBlock(this.worldPosition, state.setValue(CarWinchBlock.POWERED, powered), Block.UPDATE_CLIENTS);
    }
}
                
    /** Только смотка. Сила сигнала масштабирует скорость. */
private void reelIn(final ServerRopeStrand strand, final int power) {
    final int minPointCount = 2;
    double extension = strand.getExtension() - REEL_SPEED * (power / 15.0F);

    while (extension < 0.0 && strand.getPoints().size() > minPointCount) {
        strand.removeFirstPoint();
        extension += ServerRopeStrand.SEGMENT_LENGTH;
    }
    if (extension < 1.0 && strand.getPoints().size() <= minPointCount) {
        extension = 1.0;
    }

    strand.updateFirstSegmentExtension(extension);
}

    private void syncRopedState() {
        final BlockState state = this.getBlockState();

        if (!state.hasProperty(CarWinchBlock.ROPED)) {
            return;
        }

        final boolean roped = this.ropeHolder.isAttached();

        if (state.getValue(CarWinchBlock.ROPED) != roped) {
            this.level.setBlock(
                    this.worldPosition,
                    state.setValue(CarWinchBlock.ROPED, roped),
                    Block.UPDATE_ALL
            );
        }
    }

    private void updateRopeStrandExtension(
        final ServerRopeStrand strand,
        final int power
) {
    /*
     * При отсутствии сигнала лебёдка ничего не делает.
     */
    if (power <= 0) {
        return;
    }

    /*
     * Чем сильнее редстоун-сигнал, тем быстрее смотка.
     *
     * power = 1  -> минимальная скорость
     * power = 15 -> REEL_SPEED, то есть 0.075 блока/тик
     */
    final float movementSpeed =
            -REEL_SPEED * (power / 15.0F);

    /*
     * Проверяем текущую длину троса.
     */
    final double currentExtension =
            strand.getCurrentExtension();

    /*
     * Если трос уже достиг максимальной длины,
     * не позволяем ему становиться длиннее.
     */
    if (currentExtension > MAX_RANGE
            && movementSpeed > 0.0F) {
        return;
    }

    double extension =
            strand.getExtension()
                    + movementSpeed;

    final int minPointCount = 2;

    /*
     * Если точек осталось минимальное количество,
     * не укорачиваем трос ниже одного блока.
     */
    if (extension < 1.0D
            && strand.getPoints().size()
            == minPointCount) {
        extension = 1.0D;
    } else {
        /*
         * Убираем начальные точки при смотке.
         */
        while (extension < 0.0D
                && strand.getPoints().size()
                > minPointCount) {
            strand.removeFirstPoint();
            extension +=
                    ServerRopeStrand.SEGMENT_LENGTH;
        }

        /*
         * Минимальная длина первого сегмента.
         */
        if (extension < 1.0D
                && strand.getPoints().size()
                <= minPointCount) {
            extension = 1.0D;
        }
    }

    strand.updateFirstSegmentExtension(
            extension
    );
} {
        final int power =
                this.level.getBestNeighborSignal(this.worldPosition);

        /*
         * Без редстоуна лебёдка полностью остановлена.
         * Никакого автоматического freewheel или payout.
         */
        if (power <= 0) {
            return;
        }

        /*
         * Важно: movementSpeed НЕ final,
         * потому что ниже он ограничивается по MAX_RANGE.
         */
        float movementSpeed =
                -REEL_SPEED * (power / 15.0F);

        final double currentExtension =
                strand.getCurrentExtension();

        if (currentExtension > MAX_RANGE) {
            movementSpeed = Math.min(0.0F, movementSpeed);
        }

        double extension =
                strand.getExtension() + movementSpeed;

        final int minPointCount = 2;

        if (extension < 1.0
                && strand.getPoints().size() == minPointCount) {
            extension = 1.0;
        } else {
            while (extension < 0.0) {
                strand.removeFirstPoint();
                extension += ServerRopeStrand.SEGMENT_LENGTH;

                if (extension < 1.0
                        && strand.getPoints().size() == minPointCount) {
                    extension = 1.0;
                    break;
                }
            }

            while (extension > ServerRopeStrand.SEGMENT_LENGTH) {
                final Vec3 anchor =
                        Sable.HELPER.projectOutOfSubLevel(
                                this.level,
                                this.ropeHolder.getAttachmentPoint()
                        );

                strand.addPoint(
                        new Vector3d(
                                anchor.x,
                                anchor.y,
                                anchor.z
                        )
                );

                extension -= 1.0;
            }

            if (extension < 1.0
                    && strand.getPoints().size() <= minPointCount) {
                extension = 1.0;
            }
        }

        strand.updateFirstSegmentExtension(extension);
    }

    @Override
    public AABB getRenderBoundingBox() {
        final ClientRopeStrand rope =
                this.ropeHolder.getClientStrand();

        if (rope != null && this.ropeHolder.ownsRope()) {
            final AABB bounds = rope.getBounds();

            if (bounds != null) {
                return bounds.inflate(
                        RENDER_BOUNDING_BOX_INFLATION
                );
            }
        }

        return super.getRenderBoundingBox();
    }
}
