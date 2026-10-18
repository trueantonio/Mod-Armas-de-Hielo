package com.espadahielo;

import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Renderizadores de las entidades del mod. Solo se registra en cliente: las clases de
// renderizado no existen en el servidor. En Forge 65 los eventos EntityRenderersEvent
// viven en el bus por defecto, asi que no se pone el param bus (el default ya es el
// correcto para ellos)
@Mod.EventBusSubscriber(modid = EspadaDeHielo.MODID, value = Dist.CLIENT)
public final class ClienteMod {
    private ClienteMod() {
    }

    // La bola de hielo se dibuja como un item girando en el aire (el mismo sistema que
    // usa la bola de fuego del ghast desde 26.2, ThrownItemRenderer + ItemSupplier),
    // a escala 3 y con luz maxima para que brille como aquella
    @SubscribeEvent
    public static void alRegistrarRenderizadores(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EspadaDeHielo.ENTIDAD_BOLA_DE_HIELO.get(),
            contexto -> new ThrownItemRenderer<>(contexto, 3.0F, true));
    }
}
