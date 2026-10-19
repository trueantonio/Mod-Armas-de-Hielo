package com.espadahielo;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Paquete cliente -> servidor: "he hecho clic izquierdo mientras pilotaba al Glaciarock".
 *
 * Cuando un jugador va dentro del coloso, el clic izquierdo no es su puñetazo (ni sirve
 * para minar): el cliente lo cancela (ver ClienteMod) y avisa por aqui para que sea el
 * Glaciarock el que pegue. El paquete va vacio a proposito: el servidor no necesita saber
 * nada mas (quien pega y a quien los decide el propio coloso, ver
 * {@link Glaciarock#golpearComoJinete()})
 */
public record PaqueteAtaqueGlaciarock() {
    public static final StreamCodec<RegistryFriendlyByteBuf, PaqueteAtaqueGlaciarock> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PaqueteAtaqueGlaciarock decode(final RegistryFriendlyByteBuf buffer) {
            return new PaqueteAtaqueGlaciarock();
        }

        @Override
        public void encode(final RegistryFriendlyByteBuf buffer, final PaqueteAtaqueGlaciarock paquete) {
        }
    };
}
