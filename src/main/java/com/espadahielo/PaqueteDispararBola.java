package com.espadahielo;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Paquete cliente -> servidor: "he hecho clic izquierdo al aire con el Cetro de Hielo".
 *
 * Igual que {@link PaqueteGolpearAgua} para la espada: Minecraft no manda nada al
 * servidor cuando el clic izquierdo no acierta a ningun bloque (LeftClickEmpty es un
 * evento solo cliente), asi que el propio cliente avisa para que el servidor lance la
 * bola de hielo en la direccion que se estaba mirando.
 *
 * @param xRot inclinacion vertical de la mirada (grados) en el momento del clic
 * @param yRot rotacion horizontal de la mirada (grados) en el momento del clic
 */
public record PaqueteDispararBola(float xRot, float yRot) {
    public static final StreamCodec<RegistryFriendlyByteBuf, PaqueteDispararBola> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PaqueteDispararBola decode(final RegistryFriendlyByteBuf buffer) {
            return new PaqueteDispararBola(buffer.readFloat(), buffer.readFloat());
        }

        @Override
        public void encode(final RegistryFriendlyByteBuf buffer, final PaqueteDispararBola paquete) {
            buffer.writeFloat(paquete.xRot());
            buffer.writeFloat(paquete.yRot());
        }
    };
}
