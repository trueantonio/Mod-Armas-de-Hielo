package com.espadahielo;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Cetro de Hielo.
 *
 * Arma a distancia invocadora: 500 de durabilidad (ver
 * {@link EspadaDeHielo#DURABILIDAD_CETRO}).
 *
 * Funcionalidades:
 *  - Clic izquierdo: lanza una bola de hielo ({@link BolaDeHielo}) con comportamiento
 *    de bola de fuego del ghast: explosion al tocar bloque o entidad, potencia 3 y
 *    rompe bloques. Cada bola consume 1 de durabilidad, igual que una flecha.
 *  - Clic derecho: invocacion del elemental de hielo tras un casteo de 3 segundos.
 *    La invocacion consume un 33% de la durabilidad total. (Por implementar.)
 */
public class CetroDeHieloItem extends Item {
    public CetroDeHieloItem(final Item.Properties properties) {
        super(properties);
    }

    /**
     * Lanza una bola de hielo en la direccion (xRot, yRot) apuntada y consume 1 de
     * durabilidad del cetro.
     *
     * Se invoca desde dos vias: el clic izquierdo sobre un bloque (solo servidor, el
     * cliente cancela el minado) y el paquete propio de golpe al aire
     * (ver {@link PaqueteDispararBola}).
     *
     * @param xRot inclinacion vertical de la mirada (grados) con la que se hizo el clic
     * @param yRot rotacion horizontal de la mirada (grados) con la que se hizo el clic
     * @return true si el cetro ha actuado (el clic debe cancelarse: no sirve para minar)
     */
    public boolean intentarDisparo(final Level level, final Player player,
                                   final float xRot, final float yRot) {
        if (level.isClientSide()) {
            // El cliente solo cancela el clic; la bola la crea el servidor
            return true;
        }

        final Vec3 direccion = player.calculateViewVector(xRot, yRot);
        level.addFreshEntity(new BolaDeHielo(level, player, direccion));

        // Sonido de disparo: la misma carga de fuego que la bola del ghast, algo mas aguda
        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(),
            SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.7F, 1.3F);

        // Cada bola consume 1 de durabilidad, igual que una flecha
        player.getItemInHand(InteractionHand.MAIN_HAND)
            .hurtAndBreak(1, player, InteractionHand.MAIN_HAND);

        return true;
    }
}
