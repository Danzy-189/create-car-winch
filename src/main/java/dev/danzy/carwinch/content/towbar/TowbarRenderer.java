package dev.danzy.carwinch.content.towbar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class TowbarRenderer
        extends SafeBlockEntityRenderer<TowbarBlockEntity> {

    private static final ResourceLocation COUPLING_MODEL =
            ResourceLocation.fromNamespaceAndPath(
                    "carwinch",
                    "block/coupling"
            );

    /**
     * Модель в JSON имеет длину 16 model-units,
     * то есть один полный Minecraft-блок.
     */
    private static final float MODEL_LENGTH_IN_BLOCKS = 1.0F;

    /**
     * Поперечный масштаб сцепки.
     * Если вал слишком толстый или тонкий, меняй только это значение.
     */
    private static final float COUPLING_WIDTH = 1.0F;

    public TowbarRenderer(
            final net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context context
    ) {
    }

    @Override
    public boolean shouldRenderOffScreen(
            final TowbarBlockEntity blockEntity
    ) {
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

        if (level == null
                || !blockEntity.isCouplingOwner()
                || blockEntity.getCouplingTarget() == null) {
            return;
        }

        final TowbarBlockEntity target =
                getTarget(
                        level,
                        blockEntity.getCouplingTarget()
                );

        if (target == null) {
            return;
        }

        /*
         * Эти точки локальные для разных sublevel.
         * Сначала обязательно переводим обе в мировую систему.
         */
        final Vec3 localStart =
                blockEntity.getAttachmentPoint();

        final Vec3 localEnd =
                target.getAttachmentPoint();

        final Vec3 start =
                Sable.HELPER.projectOutOfSubLevel(
                        level,
                        localStart
                );

        final Vec3 end =
                Sable.HELPER.projectOutOfSubLevel(
                        level,
                        localEnd
                );

        final Vec3 delta = end.subtract(start);
        final double length = delta.length();

        if (length < 0.05D) {
            return;
        }

        /*
         * Модель coupling.json вертикальная:
         * от Y=0 до Y=16.
         * Поэтому исходная ось модели направлена вверх, по +Y.
         */
        final Vector3f direction =
                new Vector3f(
                        (float) (delta.x / length),
                        (float) (delta.y / length),
                        (float) (delta.z / length)
                ).normalize();

        /*
         * Загружаем именно block-модель:
         * assets/carwinch/models/block/coupling.json
         */
        final BakedModel model =
                Minecraft.getInstance()
                        .getModelManager()
                        .getModel(COUPLING_MODEL);

        if (model == null
                || model == Minecraft.getInstance()
                        .getModelManager()
                        .getMissingModel()) {
            return;
        }

        final BlockRenderDispatcher blockRenderer =
                Minecraft.getInstance()
                        .getBlockRenderer();

        final BlockState renderState =
                Blocks.IRON_BLOCK.defaultBlockState();

        final VertexConsumer consumer =
                buffer.getBuffer(
                        net.minecraft.client.renderer.RenderType.solid()
                );

        final BlockPos lightPos =
                BlockPos.containing(
                        start.x,
                        start.y,
                        start.z
                );

        final int shaftLight =
                LevelRenderer.getLightColor(
                        level,
                        lightPos
                );

        /*
         * Renderer уже находится в координатах первого фаркопа.
         * Переводим начало вала из мировых координат
         * в локальные координаты render block entity.
         */
        final double relativeX =
                start.x - blockEntity.getBlockPos().getX();

        final double relativeY =
                start.y - blockEntity.getBlockPos().getY();

        final double relativeZ =
                start.z - blockEntity.getBlockPos().getZ();

        poseStack.pushPose();

        poseStack.translate(
                relativeX,
                relativeY,
                relativeZ
        );

        /*
         * Поворачиваем вертикальную модель вдоль направления
         * от первого фаркопа ко второму.
         */
        rotatePositiveYToDirection(
                poseStack,
                direction
        );

        /*
         * coupling.json имеет размер 1x1x1 блок.
         * Масштаб по Y равен фактической длине между фаркопами.
         * Sublevel при этом вообще не перемещаются.
         */
        poseStack.scale(
                COUPLING_WIDTH,
                (float) (length / MODEL_LENGTH_IN_BLOCKS),
                COUPLING_WIDTH
        );

        /*
         * ModelBlockRenderer ожидает обычную block-модель
         * с координатами от 0 до 1.
         */
        blockRenderer.getModelRenderer().tesselateBlock(
                level,
                model,
                renderState,
                blockEntity.getBlockPos(),
                poseStack,
                consumer,
                true,
                RandomSource.create(0L),
                0L,
                overlay
        );

        poseStack.popPose();
    }

    private static void rotatePositiveYToDirection(
            final PoseStack poseStack,
            final Vector3f direction
    ) {
        final Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);

        /*
         * Если направление почти вертикальное, обычный rotationTo
         * работает нестабильно, поэтому обрабатываем обе крайние ситуации.
         */
        if (direction.dot(up) > 0.9999F) {
            return;
        }

        if (direction.dot(up) < -0.9999F) {
            poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
            return;
        }

        final Vector3f axis =
                up.cross(direction, new Vector3f()).normalize();

        final float angle =
                (float) Math.acos(
                        Math.max(
                                -1.0F,
                                Math.min(
                                        1.0F,
                                        up.dot(direction)
                                )
                        )
                );

        poseStack.mulPose(
                new org.joml.Quaternionf()
                        .fromAxisAngleRad(
                                axis.x,
                                axis.y,
                                axis.z,
                                angle
                        )
        );
    }

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
