package dev.danzy.carwinch.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.danzy.carwinch.content.winch.CarWinchBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopePoint;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import dev.simulated_team.simulated.util.SimMathUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

public final class CarWinchRopeRenderer {

    private CarWinchRopeRenderer() {
    }

    public record RopeRenderPoint(
            Quaternionf orientation,
            Vector3d position
    ) {
    }

    public static void render(
            final CarWinchBlockEntity blockEntity,
            final RopeStrandHolderBehavior ropeHolder,
            final float partialTick,
            final PoseStack poseStack,
            final MultiBufferSource buffer
    ) {
        final Level level = blockEntity.getLevel();

        if (level == null || !level.isClientSide()) {
            return;
        }

        if (ropeHolder == null || !ropeHolder.ownsRope()) {
            return;
        }

        final ClientRopeStrand clientStrand =
                ropeHolder.getClientStrand();

        if (clientStrand == null) {
            return;
        }

        final List<ClientRopePoint> points =
                clientStrand.getPoints();

        if (points == null || points.size() <= 1) {
            return;
        }

        final BlockPos ownerPos =
                blockEntity.getBlockPos();

        final SuperByteBuffer middle =
                CachedBuffers.partialFacing(
                        CarWinchPartialModels.ROPE,
                        net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState(),
                        Direction.NORTH
                );

        final SuperByteBuffer knot =
                CachedBuffers.partialFacing(
                        CarWinchPartialModels.ROPE_KNOT,
                        net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState(),
                        Direction.NORTH
                );

        final var vertexConsumer =
                buffer.getBuffer(RenderType.solid());

        final SubLevel subLevel =
                Sable.HELPER.getContaining(blockEntity);

        Pose3dc containingPose = null;

        if (subLevel instanceof ClientSubLevel clientSubLevel) {
            containingPose = clientSubLevel.renderPose();
        }

        final ObjectArrayList<RopeRenderPoint> renderPoints =
                buildRenderPoints(partialTick, points);

        if (renderPoints.isEmpty()) {
            return;
        }

        poseStack.pushPose();

        for (int i = 1; i < renderPoints.size(); i++) {
            final RopeRenderPoint point0 =
                    renderPoints.get(i - 1);

            final RopeRenderPoint point1 =
                    renderPoints.get(i);

            final Vector3d globalRenderPosition =
                    new Vector3d(point0.position());

            final Vector3d renderPosition =
                    new Vector3d(point0.position());

            final Quaternionf orientation =
                    new Quaternionf(point0.orientation());

            final double segmentLength =
                    point1.position().distance(
                            point0.position()
                    );

            if (containingPose != null) {
                containingPose.transformPositionInverse(
                        renderPosition
                );

                orientation.premul(
                        new Quaternionf(
                                containingPose.orientation()
                        ).conjugate()
                );
            }

            poseStack.pushPose();

            poseStack.translate(
                    renderPosition.x - ownerPos.getX(),
                    renderPosition.y - ownerPos.getY(),
                    renderPosition.z - ownerPos.getZ()
            );

            poseStack.mulPose(orientation);

            /*
             * Модель rope.json имеет высоту 16 пикселей.
             * Поэтому масштаб по Y растягивает её ровно по длине сегмента.
             */
            poseStack.translate(
                    -0.5D,
                    -0.5D,
                    -0.5D
            );

            final BlockPos lightPos =
                    BlockPos.containing(
                            globalRenderPosition.x,
                            globalRenderPosition.y,
                            globalRenderPosition.z
                    );

            final int worldLight =
                    LevelRenderer.getLightColor(
                            level,
                            lightPos
                    );

            /*
             * Узлы между сегментами.
             * Сейчас используется та же модель rope.json.
             */
            if (i > 1) {
                knot.light(worldLight)
                        .renderInto(
                                poseStack,
                                vertexConsumer
                        );
            }

            poseStack.translate(
                    0.0D,
                    0.5D,
                    0.0D
            );

            poseStack.scale(
                    1.0F,
                    (float) segmentLength,
                    1.0F
            );

            middle.light(worldLight)
                    .renderInto(
                            poseStack,
                            vertexConsumer
                    );

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private static ObjectArrayList<RopeRenderPoint> buildRenderPoints(
            final float partialTick,
            final List<ClientRopePoint> inputPoints
    ) {
        final ObjectArrayList<RopeRenderPoint> renderPoints =
                new ObjectArrayList<>();

        final ObjectArrayList<ClientRopePoint> points =
                new ObjectArrayList<>(inputPoints);

        /*
         * Удаляем нулевые начальные сегменты.
         */
        while (points.size() >= 2
                && points.getFirst()
                .position()
                .distanceSquared(
                        points.get(1).position()
                ) < 1.0E-3D) {
            points.removeFirst();
        }

        if (points.size() <= 1) {
            return renderPoints;
        }

        final Vector3dc firstPosition =
                points.get(0).renderPos(
                        partialTick,
                        new Vector3d()
                );

        final Vector3dc secondPosition =
                points.get(1).renderPos(
                        partialTick,
                        new Vector3d()
                );

        final Vector3d normal =
                secondPosition.sub(
                        firstPosition,
                        new Vector3d()
                ).normalize();

        final Quaternionf runningRotation;

        if (normal.dot(OrientedBoundingBox3d.UP) < 0.0D) {
            runningRotation =
                    SimMathUtils.getQuaternionfFromVectorRotation(
                            new Vector3d(0.0D, -1.0D, 0.0D),
                            normal
                    );

            runningRotation.rotateZ(
                    (float) Math.PI
            );
        } else {
            runningRotation =
                    SimMathUtils.getQuaternionfFromVectorRotation(
                            new Vector3d(0.0D, 1.0D, 0.0D),
                            normal
                    );
        }

        renderPoints.add(
                new RopeRenderPoint(
                        new Quaternionf(runningRotation),
                        new Vector3d(firstPosition)
                )
        );

        final Vector3d runningNormal =
                new Vector3d();

        final Vector3d pointBPosition =
                new Vector3d();

        final Vector3d pointAPosition =
                new Vector3d();

        for (int i = 2; i < points.size(); i++) {
            final ClientRopePoint pointA =
                    points.get(i - 1);

            final ClientRopePoint pointB =
                    points.get(i);

            runningNormal.set(
                    pointB.renderPos(
                            partialTick,
                            pointBPosition
                    )
            ).sub(
                    pointA.renderPos(
                            partialTick,
                            pointAPosition
                    )
            ).normalize();

            if (runningNormal.dot(
                    OrientedBoundingBox3d.UP
            ) < -0.15D) {
                runningRotation.set(
                        SimMathUtils
                                .getQuaternionfFromVectorRotation(
                                        new Vector3d(
                                                0.0D,
                                                -1.0D,
                                                0.0D
                                        ),
                                        runningNormal
                                )
                );

                runningRotation.rotateZ(
                        (float) Math.PI
                );
            } else {
                runningRotation.set(
                        SimMathUtils
                                .getQuaternionfFromVectorRotation(
                                        new Vector3d(
                                                0.0D,
                                                1.0D,
                                                0.0D
                                        ),
                                        runningNormal
                                )
                );
            }

            renderPoints.add(
                    new RopeRenderPoint(
                            new Quaternionf(runningRotation),
                            pointA.renderPos(
                                    partialTick,
                                    new Vector3d()
                            )
                    )
            );
        }

        renderPoints.add(
                new RopeRenderPoint(
                        new Quaternionf(runningRotation),
                        points.getLast().renderPos(
                                partialTick,
                                new Vector3d()
                        )
                )
        );

        return renderPoints;
    }
}
