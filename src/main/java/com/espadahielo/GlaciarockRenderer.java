package com.espadahielo;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.base.RenderPassInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.util.List;

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
    // Marca que se cuelga del estado de render para saber, a la hora de dibujar, si el que
    // mira ES el piloto en primera persona. La interfaz "duck" de GeckoLib (GeoRenderState)
    // no es visible en tiempo de compilacion sobre los render state de vanilla: la mete
    // GeckoLib por mixin en tiempo de ejecucion, asi que en el codigo hay que ir con cast
    private static final DataTicket<Boolean> PILOTO_EN_PRIMERA_PERSONA =
        DataTicket.create("espadahielo_glaciarock_piloto_primera_persona", Boolean.class);

    // Cuanto se levantan los brazos hacia delante (radianes) en la vista del piloto. Los
    // brazos cuelgan hacia abajo desde los hombros, asi que sin esto quedan pegados a los
    // costados y no se ven: girandolos hacia delante asoman por delante de la camara como
    // si fueran los del que va dentro. 1.5 rad = 86 grados (brazo casi horizontal)
    private static final float GIRO_BRAZOS_PRIMERA_PERSONA = 1.5F;

    // Cuanto se deja del balanceo original de los brazos (andar/golpear) encima de ese giro.
    // A 1.0 los brazos conservan toda su animacion, pero como ahora apuntan hacia delante se
    // moverian mucho; con menos se mantiene el gesto sin que pierdan la posicion
    private static final float BALANCEO_BRAZOS_PRIMERA_PERSONA = 0.35F;

    public GlaciarockRenderer(final EntityRendererProvider.Context contexto) {
        super(contexto, EspadaDeHielo.ENTIDAD_GLACIAROCK.get());
    }

    /**
     * Aparte de lo que ya saca GeckoLib, se apunta en el estado de render si esta pasada es
     * la vista del piloto en primera persona (ver {@link #PILOTO_EN_PRIMERA_PERSONA}). Se
     * hace en el estado (y no en el modelo) porque el modelo es unico y compartido entre
     * todas las copias del coloso y entre todos los jugadores
     */
    @Override
    public void extractRenderState(final Glaciarock elemental, final LivingEntityRenderState estado,
                                   final float parcial) {
        super.extractRenderState(elemental, estado, parcial);

        final Minecraft cliente = Minecraft.getInstance();
        final boolean pilotoEnPrimeraPersona = cliente.player != null
            && cliente.player == elemental.getFirstPassenger()
            && cliente.options.getCameraType().isFirstPerson();

        ((GeoRenderState) estado).addGeckolibData(PILOTO_EN_PRIMERA_PERSONA, pilotoEnPrimeraPersona);
    }

    /**
     * En la vista del piloto en primera persona no se dibuja el coloso entero: la camara va
     * a la altura de su cabeza, asi que el modelo le taparia la vista. En su lugar se deja
     * fuera la cabeza/cuerpo y las piernas y se levantan los brazos hacia delante, para que
     * el jugador vea los brazos de hielo como si fueran suyos. En tercera persona (F5) y
     * para el resto de jugadores se dibuja normal, como cualquier otra criatura
     */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void submit(final LivingEntityRenderState estado, final PoseStack pila,
                       final SubmitNodeCollector tareas, final CameraRenderState camara) {
        if (!esPilotoEnPrimeraPersona(estado)) {
            super.submit(estado, pila, tareas, camara);
            return;
        }

        final RenderPassInfo.BoneUpdater recorteDePiloto = (info, huesos) -> {
            // Fuera el bloque cabeza/cuerpo (que va del pecho a la coronilla) y las piernas,
            // que taparian la camara; se saltan tambien sus hijos por si acaso
            huesos.ifPresent("cabeza", hueso -> hueso.skipRender(true).skipChildrenRender(true));
            huesos.ifPresent("pierna izquierda", hueso -> hueso.skipRender(true).skipChildrenRender(true));
            huesos.ifPresent("pie derecho", hueso -> hueso.skipRender(true).skipChildrenRender(true));

            // Los brazos se giran hacia delante conservando una parte del balanceo original
            huesos.ifPresent("mano derecha", hueso -> hueso.setRotX(
                GIRO_BRAZOS_PRIMERA_PERSONA + hueso.getRotX() * BALANCEO_BRAZOS_PRIMERA_PERSONA));
            huesos.ifPresent("mano izquierda", hueso -> hueso.setRotX(
                GIRO_BRAZOS_PRIMERA_PERSONA + hueso.getRotX() * BALANCEO_BRAZOS_PRIMERA_PERSONA));
        };

        // GeoRenderer#performRenderPass con tipos crudos a proposito: con tipos genericos
        // habria que escribir RenderPassInfo<LivingEntityRenderState>, y eso no compila
        // porque, en tiempo de compilacion, LivingEntityRenderState no es un GeoRenderState
        ((GeoRenderer) this).performRenderPass((GeoRenderState) estado, pila, tareas, camara,
            List.of(recorteDePiloto));
    }

    private static boolean esPilotoEnPrimeraPersona(final LivingEntityRenderState estado) {
        return Boolean.TRUE.equals(((GeoRenderState) estado)
            .getOrDefaultGeckolibData(PILOTO_EN_PRIMERA_PERSONA, Boolean.FALSE));
    }
}
