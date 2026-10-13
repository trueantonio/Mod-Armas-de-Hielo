package com.espadahielo;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Eventos de juego (bus de Forge) del mod
@Mod.EventBusSubscriber(modid = EspadaDeHielo.MODID)
public final class EventosMod {
    private EventosMod() {
    }

    // Al golpear a una entidad con la Espada de Hielo en la mano principal,
    // le aplica un debuff de Ralentizacion
    @SubscribeEvent
    public static void alGolpear(final AttackEntityEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return; // La logica solo se ejecuta en el servidor
        }
        if (!(event.getTarget() instanceof LivingEntity objetivo)) {
            return;
        }
        if (!event.getEntity().getMainHandItem().is(EspadaDeHielo.ESPADA_DE_HIELO.get())) {
            return;
        }

        objetivo.addEffect(new MobEffectInstance(
            MobEffects.SLOWNESS,
            EspadaDeHielo.TICKS_RALENTIZACION,
            EspadaDeHielo.NIVEL_RALENTIZACION
        ));
    }
}
