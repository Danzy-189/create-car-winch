package dev.danzy.carwinch.content.towbar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
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
 * Главная тонкость — система координат. Фаркоп на собранной
 * конструкции рисуется внутри рендер-пространства своего sublevel,
 * а второй фаркоп живёт в другом sublevel. Поэтому обе точки
 * сначала переводятся в общее глобальное пространство, а затем
 * возвращаются в рендер-пространство того sublevel, в котором
 * рисуется сам блок. Без этого обратного преобразования вал
 * уезжает в сторону на смещение конструкции и его не видно.
 * Тот же приём использует рендерер троса.
 *
 * Берётся именно блок create:shaft, поэтому вал выглядит и
 * текстурируется так же, как обычный вал в Create, и автоматически
 * подхватывает ресурспаки. Модель выкладывается сегментами по
 * одному блоку вдоль оси сцепки.
 */
public class TowbarRenderer
        extends SafeBlockEntityRenderer<TowbarBlockEntity> {

    private static final ResourceLocation SHAFT_ID =
            ResourceLocation.fromNamespaceAndPath("create", "shaft");

    /** Длина одного сегмента модели вала в блоках. */
    private static final double SEGMENT_LENGTH = 1.0D;

    /** Короче этого направление вала не определить. */
    private static final double MIN_RENDER_LENGTH = 0.05D;

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

        final TowbarBlockEntity target =
                getTarget(level, blockEntity.getCouplingTarget());

        if (target == null) {
            return;
        }

        final BlockState shaftState = shaftState();

        if (shaftState == null) {
            return;
        }

        /*
         * Точки крепления локальны для разных sublevel,
         * поэтому обе переводим в глобальное пространство.
         */
        final Vec3 globalStart = Sable.HELPER.projectOutOfSubLevel(
                level,
                blockEntity.getAttachmentPoint()
        );

        final Vec3 globalEnd = Sable.HELPER.projectOutOfSubLevel(
                level,
                target.getAttachmentPoint()
        );

        final Vector3d renderStart =
                new Vector3d(globalStart.x, globalStart.y, globalStart.z);

        final Vector3d renderEnd =
                new Vector3d(globalEnd.x, globalEnd.y, globalEnd.z);

        /*
         * PoseStack уже стоит в рендер-пространстве sublevel этого блока,
         * так что глобальные точки нужно вернуть в это же пространство.
         */
        final Pose3dc containingPose = containingRenderPose(blockEntity);

        if (containingPose != null) {
            containingPose.transformPositionInverse(renderStart);
            containingPose.transformPositionInverse(renderEnd);
        }

        final Vector3d delta =
                new Vector3d(renderEnd).sub(renderStart);

        final double length = delta.length();

        if (length < MIN_RENDER_LENGTH) {
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

        final BlockRenderDispatcher blockRenderer =
                Minecraft.getInstance().getBlockRenderer();

        final int shaftLight = LevelRenderer.getLightColor(
                level,
                BlockPos.containing(
                        globalStart.x,
                        globalStart.y,
                        globalStart.z
                )
        );

        // Renderer уже стоит в координатах блока, поэтому смещаемся относительно него.
        final BlockPos origin = blockEntity.getBlockPos();

        poseStack.pushPose();

        poseStack.translate(
                renderStart.x - origin.getX(),
                renderStart.y - origin.getY(),
                renderStart.z - origin.getZ()
        );

        poseStack.mulPose(rotationFromPositiveX(direction));

        final int fullSegments = (int) Math.floor(length / SEGMENT_LENGTH);
        final double remainder = length - fullSegments * SEGMENT_LENGTH;

        for (int segment = 0; segment < fullSegments; segment++) {
            renderSegment(
                    blockRenderer,
                    shaftState,
                    poseStack,
                    buffer,
                    shaftLight,
                    overlay,
                    segment * SEGMENT_LENGTH,
                    1.0F
            );
        }

        if (remainder > 0.01D) {
            renderSegment(
                    blockRenderer,
                    shaftState,
                    poseStack,
                    buffer,
                    shaftLight,
                    overlay,
                    fullSegments * SEGMENT_LENGTH,
                    (float) (remainder / SEGMENT_LENGTH)
            );
        }

        poseStack.popPose();
    }

    /**
     * Рендер-поза sublevel, в котором находится блок,
     * или null, если блок стоит в обычном мире.
     */
    @Nullable
    private static Pose3dc containingRenderPose(
            final TowbarBlockEntity blockEntity
    ) {
        final SubLevel subLevel =
                Sable.HELPER.getContaining(blockEntity);

        return subLevel instanceof ClientSubLevel clientSubLevel
                ? clientSubLevel.renderPose()
                : null;
    }

    private static void renderSegment(
            final BlockRenderDispatcher blockRenderer,
            final BlockState shaftState,
            final PoseStack poseStack,
            final MultiBufferSource buffer,
            final int light,
            final int overlay,
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

        blockRenderer.renderSingleBlock(
                shaftState,
                poseStack,
                buffer,
                light,
                overlay
        );

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

    @Nullable
    private static TowbarBlockEntity getTarget(
            final Level level,
            final BlockPos pos
    ) {
        return level.getBlockEntity(pos)
                instanceof TowbarBlockEntity towbar
                ? towbar
                : null;
    }
}
