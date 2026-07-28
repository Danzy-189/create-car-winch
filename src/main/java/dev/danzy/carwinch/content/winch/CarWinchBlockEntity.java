package dev.danzy.carwinch.content.winch;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.danzy.carwinch.registry.CWSounds;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;

public class CarWinchBlockEntity extends SmartBlockEntity
        implements RopeStrandHolderBlockEntity {

    /**
     * Максимальная длина троса в блоках.
     */
    public static final double MAX_RANGE = 18.0;

    /**
     * Скорость смотки при максимальном сигнале редстоуна.
     * Старое значение 0.09F уменьшено в 1.2 раза:
     * 0.09 / 1.2 = 0.075F.
     */
    public static final float REEL_SPEED = 0.075F;

    /**
     * Оставлены для совместимости с предыдущей логикой.
     * Автоматическая выдача троса без сигнала не используется.
     */
    public static final float PAYOUT_SPEED = 0.16F;
    public static final double SLACK_TOLERANCE = 1.03D;

    /**
     * Запас области рендера вокруг физического троса.
     */
    public static final double RENDER_BOUNDING_BOX_INFLATION = 8.0D;

    private RopeStrandHolderBehavior ropeHolder;

    /**
     * Косметическое вращение барабана на клиенте.
     */
    public final LerpedFloat drumAngle = LerpedFloat.angular();

    private float clientDrumSpeed;

    /**
     * Предыдущее состояние редстоун-сигнала.
     * Используется, чтобы звук играл только при включении.
     */
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
        behaviours.add(
                this.ropeHolder =
                        new RopeStrandHolderBehavior(this)
        );
    }

    public RopeStrandHolderBehavior getRopeHolder() {
        return this.ropeHolder;
    }

    @Override
    public RopeStrandHolderBehavior getBehavior() {
        return this.ropeHolder;
    }

    /**
     * Точка выхода троса из барабана.
     */
    @Override
    public Vec3 getAttachmentPoint(
            final BlockPos pos,
            final BlockState state
    ) {
        final Direction facing =
                state.getValue(CarWinchBlock.FACING);

        return pos.getCenter().add(
                Vec3.atLowerCornerOf(
                        facing.getNormal()
                ).scale(0.44D)
        );
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level == null) {
            return;
        }

        /*
         * Клиентская часть:
         * вращаем барабан только при наличии POWERED.
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
         * Серверная часть:
         * обновляем состояние модели лебёдки.
         */
        this.syncRopedState();

        /*
         * Получаем силу редстоун-сигнала от 0 до 15.
         */
        final int power =
                this.level.getBestNeighborSignal(
                        this.worldPosition
                );

        final boolean powered = power > 0;

        /*
         * Звук запускается только при переходе:
         *
         * false -> true
         *
         * Поэтому он не будет проигрываться каждый тик.
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

    /**
     * Обновляет состояние модели:
     *
     * false: winch.json
     * true: winch_1.json
     */
    private void syncRopedState() {
        final BlockState state =
                this.getBlockState();

        if (!state.hasProperty(CarWinchBlock.ROPED)) {
            return;
        }

        final boolean roped =
                this.ropeHolder.isAttached();

        if (state.getValue(CarWinchBlock.ROPED)
                != roped) {
            this.level.setBlock(
                    this.worldPosition,
                    state.setValue(
                            CarWinchBlock.ROPED,
                            roped
                    ),
                    Block.UPDATE_ALL
            );
        }
    }

    /**
     * Сматывает трос только при наличии редстоун-сигнала.
     *
     * Сигнал 0:
     * лебёдка полностью остановлена.
     *
     * Сигнал 1:
     * минимальная скорость.
     *
     * Сигнал 15:
     * REEL_SPEED = 0.075 блока за тик.
     */
    private void updateRopeStrandExtension(
            final ServerRopeStrand strand,
            final int power
    ) {
        /*
         * Без редстоуна ничего не меняем.
         * Freewheel и автоматическая выдача троса отключены.
         */
        if (power <= 0) {
            return;
        }

        /*
         * Скорость зависит от силы сигнала.
         * Знак минус означает сматывание.
         */
        final float movementSpeed =
                -REEL_SPEED * (power / 15.0F);

        /*
         * Не даём тросу стать длиннее MAX_RANGE.
         *
         * Сейчас движение только внутрь, поэтому это условие
         * срабатывает только как защитная проверка.
         */
        final double currentExtension =
                strand.getCurrentExtension();

        if (currentExtension >= MAX_RANGE
                && movementSpeed > 0.0F) {
            return;
        }

        double extension =
                strand.getExtension()
                        + movementSpeed;

        final int minPointCount = 2;

        /*
         * Не позволяем удалить последний обязательный сегмент.
         */
        if (extension < 1.0D
                && strand.getPoints().size()
                == minPointCount) {
            extension = 1.0D;
        } else {
            /*
             * При сматывании удаляем начальные точки,
             * когда первый сегмент стал отрицательным.
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
    }

    @Override
    public AABB getRenderBoundingBox() {
        final ClientRopeStrand rope =
                this.ropeHolder.getClientStrand();

        if (rope != null
                && this.ropeHolder.ownsRope()) {
            final AABB bounds =
                    rope.getBounds();

            if (bounds != null) {
                return bounds.inflate(
                        RENDER_BOUNDING_BOX_INFLATION
                );
            }
        }

        return super.getRenderBoundingBox();
    }
}
