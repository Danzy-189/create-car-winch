package dev.danzy.carwinch.content.towbar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Рисует сцепку между двумя фаркопами как вал Create.
 *
 * Здесь есть две неочевидные вещи, каждая из которых по отдельности
 * приводит к тому, что вала просто не видно.
 *
 * 1. Кинетические блоки Create не выдают геометрию через
 *    BlockRenderDispatcher#renderSingleBlock: их модель рисует
 *    KineticBlockEntityRenderer через CachedBuffers, либо Flywheel
 *    через инстансинг. Поэтому здесь используется ровно тот же путь,
 *    что и у самого Create — CachedBuffers.block с компартментом
 *    KineticBlockEntityRenderer.KINETIC_BLOCK.
 *
 * 2. Система координат. PoseStack рендерера стоит в рендер-пространстве
 *    того sublevel, в котором находится блок, а второй фаркоп живёт в
 *    другом sublevel со своей позой. Sable.HELPER#projectOutOfSubLevel
 *    для этого не годится: он считает по logicalPose, то есть по
 *    тиковой, а не по кадровой позе. Точка второго фаркопа переводится
 *    его renderPose в глобальное пространство и обратной renderPose
 *    своего sublevel — в локальное. Тот же приём использует автосцепка
 *    в Create Simurail.
 *
 * Если результат получился неправдоподобным (например, второй фаркоп
 * ещё не подгрузился на клиенте), вал всё равно рисуется — по
 * направлению самого фаркопа и на запомненную длину.
 */
public class TowbarRenderer
        extends SafeBlockEntityRenderer<TowbarBlockEntity> {

    private static final ResourceLocation SHAFT_ID =
            ResourceLocation.fromNamespaceAndPath("create", "shaft");

    /** Длина одного сегмента модели вала в блоках. */
    private static final double SEGMENT_LENGTH = 1.0D;

    /** Короче этого направление вала не определить. */
    private static final double MIN_RENDER_LENGTH = 0.05D;

    /** Запас на рассинхрон физики и кадра. */
    private static final double LENGTH_TOLERANCE = 1.0D;

    @Nullable
    private static BlockState cachedShaftState;

    public TowbarRenderer(final BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRenderOffScreen(final TowbarBlockEntity blockEntity) {
        return true;
    }

    @Override
    public boolean shouldRender(
            final TowbarBlockEntity blockEntity,
            final Vec3 cameraPos
    ) {
        return true;
    }

    @Override
    protected void renderSafe(
            final TowbarBlockEntity blockEntity,
            final float partialTicks,
            final PoseStack poseStack,
            final MultiBufferSource buffer,
            final int light,
            final int overlay
    ) {
        final Level level = blockEntity.getLevel();

        // Рисует только владелец сцепки, иначе вал был бы нарисован дважды.
        if (level == null
                || !blockEntity.isCouplingOwner()
                || blockEntity.getCouplingTarget() == null) {
            return;
        }

        final BlockState shaftState = shaftState();

        if (shaftState == null) {
            return;
        }

        /*
         * Начало берётся как есть: PoseStack уже стоит в том же
         * пространстве, в котором заданы координаты этого блока.
         */
        final Vec3 startPoint = blockEntity.getAttachmentPoint();

        final Vector3d start = new Vector3d(
                startPoint.x,
                startPoint.y,
                startPoint.z
        );

        Vector3d end = targetEnd(blockEntity, level, partialTicks);

        if (end == null || !plausible(start, end)) {
            end = facingEnd(blockEntity, start);
        }

        final Vector3d delta = new Vector3d(end).sub(start);

        final double length = delta.length();

        if (!Double.isFinite(length) || length < MIN_RENDER_LENGTH) {
            return;
        }

        /*
         * Направление берётся из уже преобразованных точек, поэтому вал
         * следует за наклоном и поворотом конструкции автоматически.
         * Горизонтальность самой сцепки обеспечивает констрейнт, а не рендер.
         */
        final Vector3f direction = new Vector3f(
                (float) (delta.x / length),
                (float) (delta.y / length),
                (float) (delta.z / length)
        );

        final VertexConsumer vertexConsumer =
                buffer.getBuffer(RenderType.solid());

        final BlockPos origin = blockEntity.getBlockPos();

        poseStack.pushPose();

        poseStack.translate(
                start.x - origin.getX(),
                start.y - origin.getY(),
                start.z - origin.getZ()
        );

        poseStack.mulPose(rotationFromPositiveX(direction));

        final int fullSegments =
                (int) Math.floor(length / SEGMENT_LENGTH);

        for (int segment = 0; segment < fullSegments; segment++) {
            renderSegment(
                    shaftState,
                    poseStack,
                    vertexConsumer,
                    light,
                    segment * SEGMENT_LENGTH,
                    1.0F
            );
        }

        final double remainder =
                length - fullSegments * SEGMENT_LENGTH;

        if (remainder > 0.01D) {
            if (fullSegments == 0) {
                // Сцепка короче сегмента: единственное место, где нужен масштаб.
                renderSegment(
                        shaftState,
                        poseStack,
                        vertexConsumer,
                        light,
                        0.0D,
                        (float) (remainder / SEGMENT_LENGTH)
                );
            } else {
                /*
                 * Остаток закрывается целым сегментом, придвинутым к концу:
                 * перекрытие незаметно, зато текстура не растягивается.
                 */
                renderSegment(
                        shaftState,
                        poseStack,
                        vertexConsumer,
                        light,
                        length - SEGMENT_LENGTH,
                        1.0F
                );
            }
        }

        poseStack.popPose();
    }

    /**
     * Точка крепления второго фаркопа в пространстве первого,
     * или null, если второй фаркоп недоступен на клиенте.
     */
    @Nullable
    private static Vector3d targetEnd(
            final TowbarBlockEntity blockEntity,
            final Level level,
            final float partialTicks
    ) {
        final BlockPos targetPos = blockEntity.getCouplingTarget();

        if (targetPos == null
                || !(level.getBlockEntity(targetPos)
                        instanceof TowbarBlockEntity target)) {
            return null;
        }

        final Vec3 endPoint = target.getAttachmentPoint();

        final Vector3d end = new Vector3d(
                endPoint.x,
                endPoint.y,
                endPoint.z
        );

        final ClientSubLevel ownSubLevel =
                Sable.HELPER.getContainingClient(blockEntity);

        final ClientSubLevel targetSubLevel =
                Sable.HELPER.getContainingClient(target);

        // Один и тот же sublevel (или оба в обычном мире) — пересчёт не нужен.
        if (ownSubLevel == targetSubLevel) {
            return end;
        }

        if (targetSubLevel != null) {
            targetSubLevel.renderPose(partialTicks)
                    .transformPosition(end);
        }

        if (ownSubLevel != null) {
            ownSubLevel.renderPose(partialTicks)
                    .transformPositionInverse(end);
        }

        return end;
    }

    /**
     * Запасной вариант: вал запомненной длины по направлению фаркопа.
     * Нужен, чтобы сцепку было видно даже когда второй конец
     * ещё не пришёл на клиент.
     */
    private static Vector3d facingEnd(
            final TowbarBlockEntity blockEntity,
            final Vector3d start
    ) {
        final Direction facing =
                blockEntity.getBlockState()
                        .getValue(TowbarBlock.FACING);

        double nominal = blockEntity.getCouplingLength();

        if (!(nominal >= TowbarBlockEntity.MIN_COUPLING_LENGTH)) {
            nominal = 1.0D;
        }

        return new Vector3d(start).add(
                facing.getStepX() * nominal,
                facing.getStepY() * nominal,
                facing.getStepZ() * nominal
        );
    }

    /**
     * Отсекает бессмысленные расстояния: если преобразование координат
     * почему-то не сработало, лучше нарисовать вал по направлению
     * фаркопа, чем тянуть его через полкарты.
     */
    private static boolean plausible(
            final Vector3d start,
            final Vector3d end
    ) {
        final double distance = start.distance(end);

        return Double.isFinite(distance)
                && distance >= MIN_RENDER_LENGTH
                && distance <= TowbarBlockEntity.MAX_COUPLING_LENGTH
                        + LENGTH_TOLERANCE;
    }

    /**
     * Один сегмент вала.
     *
     * Модель берётся тем же способом, что и в самом Create: через
     * CachedBuffers и компартмент кинетических блоков. Обычный
     * renderSingleBlock здесь не работает — у кинетических блоков
     * Create нет геометрии в стандартном проходе.
     */
    private static void renderSegment(
            final BlockState shaftState,
            final PoseStack poseStack,
            final VertexConsumer vertexConsumer,
            final int light,
            final double offsetAlongAxis,
            final float lengthScale
    ) {
        poseStack.pushPose();

        /*
         * Модель вала занимает куб 0..1 и центрирована по Y и Z,
         * поэтому сдвигаем её на -0.5, чтобы ось совпала с линией сцепки.
         */
        poseStack.translate(offsetAlongAxis, -0.5D, -0.5D);

        if (lengthScale != 1.0F) {
            poseStack.scale(lengthScale, 1.0F, 1.0F);
        }

        CachedBuffers.block(
                        KineticBlockEntityRenderer.KINETIC_BLOCK,
                        shaftState
                )
                .light(light)
                .renderInto(poseStack, vertexConsumer);

        poseStack.popPose();
    }

    /**
     * Вал Create, развёрнутый вдоль оси X.
     */
    @Nullable
    private static BlockState shaftState() {
        if (cachedShaftState != null) {
            return cachedShaftState;
        }

        final Block shaft = BuiltInRegistries.BLOCK.get(SHAFT_ID);

        if (shaft == null || shaft == Blocks.AIR) {
            return null;
        }

        BlockState state = shaft.defaultBlockState();

        if (state.hasProperty(BlockStateProperties.AXIS)) {
            state = state.setValue(
                    BlockStateProperties.AXIS,
                    Direction.Axis.X
            );
        }

        cachedShaftState = state;
        return state;
    }

    /**
     * Поворот, переводящий +X в заданное направление.
     * Случай строго противоположного направления обрабатываем отдельно:
     * rotationTo на антипараллельных векторах неустойчив.
     */
    private static Quaternionf rotationFromPositiveX(final Vector3f direction) {
        final Vector3f axisX = new Vector3f(1.0F, 0.0F, 0.0F);

        final float dot = axisX.dot(direction);

        if (dot > 0.9999F) {
            return new Quaternionf();
        }

        if (dot < -0.9999F) {
            return new Quaternionf()
                    .fromAxisAngleRad(0.0F, 1.0F, 0.0F, (float) Math.PI);
        }

        return new Quaternionf().rotationTo(axisX, direction);
    }
}
