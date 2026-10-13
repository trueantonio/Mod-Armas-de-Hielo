package com.espadahielo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Solo cliente. Pinta el tinte azul de "congelado" sobre las criaturas que estan heladas del
// todo. Minecraft NO trae ese tinte para las criaturas: el azul de congelacion solo se pinta
// en la pantalla del jugador (HUD), no en el cuerpo de los mobs (a los mobs solo se les nota
// el temblor). Por eso lo dibujamos nosotros: al terminar el juego de pintar el mob, volvemos
// a enviar su mismo modelo con una textura translucida y un color azul claro por encima.
@Mod.EventBusSubscriber(modid = EspadaDeHielo.MODID, value = Dist.CLIENT)
public final class RenderCongelado {
    private RenderCongelado() {
    }

    // Color del tinte (formato ARGB). El alfa (0x8C = 55%) deja ver la textura del mob por
    // debajo, pero con un tono azul-hielo claro. El azul lo hace parecer helado y, al ser
    // "emissive", brilla aunque el mob este a la sombra o de noche
    private static final int TINTE_CONGELADO = 0x8C9FD8FF;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @SubscribeEvent
    public static void alRenderizar(final RenderLivingEvent.Post evento) {
        final LivingEntityRenderState estado = evento.getState();

        // Solo si esta congelado del todo (140+ ticks, que es lo que provoca nuestro rayo) y
        // ademas esta vivo y de pie. En tumbado/moribundo/giro de ataque no reproducimos bien
        // la postura, asi que ahi simplemente no pintamos el tinte
        if (estado == null || !estado.isFullyFrozen || estado.deathTime > 0.0F
            || estado.isAutoSpinAttack || estado.hasPose(Pose.SLEEPING)) {
            return;
        }

        final LivingEntityRenderer renderer = evento.getRenderer();
        final PoseStack pose = evento.getPoseStack();
        final SubmitNodeCollector colector = evento.getNodeCollector();
        if (renderer == null || pose == null || colector == null) {
            return;
        }

        final Identifier textura = renderer.getTextureLocation(estado);
        if (textura == null) {
            return;
        }

        final RenderType tipo = RenderTypes.entityTranslucentEmissive(textura);
        final float escala = estado.scale;

        // La misma transformacion que usa el juego para pintar el modelo (LivingEntityRenderer
        // .submit): escala del mob, giro del cuerpo (incluido el temblor de congelado), volteo
        // del modelo y bajada hasta los pies. Si esto no coincide, el tinte sale desplazado
        pose.pushPose();
        try {
            pose.scale(escala, escala, escala);

            final float giroCuerpo = estado.bodyRot
                + (float) (Math.cos((double) Mth.floor(estado.ageInTicks) * 3.25D) * Math.PI * 0.4D);
            pose.mulPose(Axis.YP.rotationDegrees(180.0F - giroCuerpo));

            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(0.0F, -1.501F, 0.0F);

            // Un pelin mas grande que el modelo original, para que las dos capas no parpadeen
            // al quedar exactamente pegadas (z-fighting)
            pose.scale(1.02F, 1.02F, 1.02F);

            colector.submitModel(
                renderer.getModel(), estado, pose, tipo,
                estado.lightCoords, OverlayTexture.NO_OVERLAY, TINTE_CONGELADO, null
            );
        } catch (final Throwable fallo) {
            // Si cualquier cosa fallara, simplemente no se pinta el tinte (el mod sigue igual)
        } finally {
            pose.popPose();
        }
    }
}
