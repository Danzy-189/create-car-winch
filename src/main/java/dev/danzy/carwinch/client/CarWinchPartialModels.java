package dev.danzy.carwinch.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.danzy.carwinch.CarWinch;
import net.minecraft.resources.ResourceLocation;

public final class CarWinchPartialModels {

    public static final PartialModel ROPE =
            PartialModel.of(
                    ResourceLocation.fromNamespaceAndPath(
                            CarWinch.ID,
                            "block/rope/rope"
                    )
            );

    /*
     * Отдельного узла пока нет, поэтому используем ту же модель.
     * Если позже сделаешь knot.json, поменяешь только эту строку.
     */
    public static final PartialModel ROPE_KNOT = ROPE;

    private CarWinchPartialModels() {
    }

    public static void init() {
        // Принудительно загружает класс и его partial-модели.
    }
}
