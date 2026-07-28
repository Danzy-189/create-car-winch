package dev.danzy.carwinch.registry;

import dev.danzy.carwinch.CarWinch;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CWSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(
                    Registries.SOUND_EVENT,
                    CarWinch.ID
            );

    public static final DeferredHolder<SoundEvent, SoundEvent> WINCH_SOUND =
            SOUNDS.register(
                    "winch_sound",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(
                                    CarWinch.ID,
                                    "winch_sound"
                            )
                    )
            );

    private CWSounds() {
    }
}
