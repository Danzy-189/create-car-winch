package dev.danzy.carwinch.content.towbar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.ryanhcode.sable.Sable;
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
import org.joml.Vector3f;

/**
 * Рисует сцепку между двумя фаркопами как горизонтальный вал Create
 * фиксированной длины.
 *
 * Берётся именно блок create:shaft с AXIS=X, поэтому вал выглядит и
 * текстурируется точно так же, как обычный вал в Create, и автоматически
 * подхватывает ресурспаки. Модель выкладывается сегментами по одному
 * блоку вдоль оси сцепки.
 */
public class TowbarRenderer
        extends SafeBlockEntityRenderer<TowbarBlockEntity> {

    private static final ResourceLocation SHAFT_ID =
            ResourceLocation.fromNamespaceAndPath("create", "shaft");

    /** Длина одного сегмента модели вала в блоках. */
    private static final double SEGMENT_LENGTH = 1.0D;

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

        /*
         * Точки крепления локальны для разных sublevel,
         * поэтому обе переводим в мировую систему.
         */
        final Vec3 start = Sable.HELPER.projectOutOfSubLevel(
                level,
                blockEntity.getAttachmentPoint()
        );

        final Vec3 end = Sable.HELPER.projectOutOfSubLevel(
                level,
                target.getAttachmentPoint()
        );

        final Vec3 delta = end.subtract(start);

        // Вал горизонтальный: вертикальную составляющую игнорируем.
        final double horizontalLength =
                Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        if (horizontalLength < 0.05D) {
            return;
        }

        final Vector3f direction = new Vector3f(
                (float) (delta.x / horizontalLength),
                0.0F,
                (float) (delta.z / horizontalLength)
        );

        final BlockState shaftState = shaftState();

        if (shaftState == null) {
            return;
        }

        final BlockRenderDispatcher blockRenderer =
                Minecraft.getInstance().getBlockRenderer();

        final BlockPos lightPos = BlockPos.containing(start.x, start.y, start.z);

        final int shaftLight = LevelRenderer.getLightColor(level, lightPos);

        // Renderer уже стоит в координатах блока, поэтому смещаемся относительно него.
        final BlockPos origin = blockEntity.getBlockPos();

        poseStack.pushPose();

        poseStack.translate(
                start.x - origin.getX(),
                start.y - origin.getY(),
                start.z - origin.getZ()
        );

        poseStack.mulPose(rotationFromPositiveX(direction));

        /*
         * Длина всегда номинальная, а не фактическая: сцепка жёсткая,
         * а физика удерживает фаркопы на этом же расстоянии.
         */
        final double length = blockEntity.getCouplingLength();

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
     * Поворот, переводящий +X в заданное горизонтальное направление.
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
