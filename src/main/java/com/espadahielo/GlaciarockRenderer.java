package com.espadahielo;

import com.geckolib.renderer.GeoEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Renderizador del Glaciarock con GeckoLib.
 *
 * No hace falta model class: el constructor de dos argumentos de
 * {@link GeoEntityRenderer} crea por su cuenta el modelo por defecto, que busca los
 * archivos en {@code assets/espadahielo/entity/} con el nombre del id de la entidad
 * (glaciarock.geo.json, glaciarock.animation.json) y la textura en
 * {@code assets/espadahielo/textures/entity/glaciarock.png}.
 *
 * Tampoco se le pone escala a mano: la hitbox ya la agranda el atributo SCALE (5x) y el
 * renderizador lee esa misma escala del estado de render, asi que modelo y hitbox van
 * sincronizados (si se añadiera aqui otra escala, la doblaria)
 */
public class GlaciarockRenderer extends GeoEntityRenderer<Glaciarock, LivingEntityRenderState> {
    public GlaciarockRenderer(final EntityRendererProvider.Context contexto) {
        super(contexto, EspadaDeHielo.ENTIDAD_GLACIAROCK.get());
    }
}
