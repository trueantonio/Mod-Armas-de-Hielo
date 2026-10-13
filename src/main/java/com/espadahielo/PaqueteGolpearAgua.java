package com.espadahielo;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Paquete cliente -> servidor: "he hecho clic izquierdo al aire con la Espada de Hielo".
 *
 * Minecraft solo avisa al servidor cuando el clic acierta a un bloque o a una entidad;
 * al golpear el aire (por ejemplo, apuntando a agua profunda desde la superficie, sin
 * ningun bloque solido a la vista) no manda nada, y por eso el propio cliente envia
 * este paquete. Los angulos de mira permiten al servidor congele exactamente el agua
 * que el jugador veia en el momento del clic.
 *
 * @param xRot inclinacion vertical de la mirada (grados) en el momento del clic
 * @param yRot rotacion horizontal de la mirada (grados) en el momento del clic
 */
public record PaqueteGolpearAgua(float xRot, float yRot) {
    public static final StreamCodec<RegistryFriendlyByteBuf, PaqueteGolpearAgua> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PaqueteGolpearAgua decode(final RegistryFriendlyByteBuf buffer) {
            return new PaqueteGolpearAgua(buffer.readFloat(), buffer.readFloat());
        }

        @Override
        public void encode(final RegistryFriendlyByteBuf buffer, final PaqueteGolpearAgua paquete) {
            buffer.writeFloat(paquete.xRot());
            buffer.writeFloat(paquete.yRot());
        }
    };
}
