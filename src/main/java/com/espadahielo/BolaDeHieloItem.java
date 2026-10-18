package com.espadahielo;

import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.Item;

/**
 * Item interno de la Bola de Hielo.
 *
 * No es un objeto que el jugador pueda obtener: existe para que el proyectil
 * {@link BolaDeHielo} (que es ItemSupplier, igual que la bola de fuego del ghast)
 * tenga un item que pintar. El renderizador de tipo ThrownItemRenderer dibuja el
 * modelo de este item girando al ritmo del proyectil, y su textura turquesa es la
 * que le da el aspecto de bola de fuego helada.
 */
public class BolaDeHieloItem extends Item {
    public BolaDeHieloItem(final Item.Properties properties) {
        super(properties);
    }
}
