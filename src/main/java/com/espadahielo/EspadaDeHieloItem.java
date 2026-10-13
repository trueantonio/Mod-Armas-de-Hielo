package com.espadahielo;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Espada de Hielo.
 *
 * Se fabrica con el patron vertical de siempre (hielo, hielo, palo) y tiene las mismas
 * estadisticas que una espada de hierro (250 de durabilidad, 6 de dano, 1.6 de velocidad
 * de ataque).
 *
 * Efectos especiales:
 *  - Al golpear el agua con clic izquierdo congela un area con forma aleatoria en el
 *    bloque de agua al que se apunta (siempre enfrente del jugador, nunca encima suyo).
 *  - Al golpear a una entidad aplica el efecto de Ralentizacion (ver EspadaDeHielo).
 */
public class EspadaDeHieloItem extends Item {
    /**
     * Formas (ancho X x fondo Z) que puede tener el area de hielo. En cada golpe se elige
     * una al azar: 3x2, 2x3, 3x3, 1x3 y 3x1 (de 3 a 9 bloques de hielo como maximo).
     */
    public static final int[][] PATRONES_HELADA = {
        {3, 2}, {2, 3}, {3, 3}, {1, 3}, {3, 1}
    };

    // Maximo de saltos del rayo para descartar los bloques que ocupa el propio jugador
    private static final int MAX_SALTOS_RAYO = 8;

    // Margen horizontal de la columna prohibida sobre el jugador: el hielo jamas debe
    // aparecer dentro de su cuerpo ni en su columna superior, ni siquiera mirando arriba
    private static final double MARGEN_COLUMNA = 0.5;

    public EspadaDeHieloItem(final Item.Properties properties) {
        // Mismas propiedades que una espada de hierro: durabilidad, reparacion con hierro,
        // encantabilidad, reglas de herramienta y atributos de ataque identicos
        super(properties.sword(ToolMaterial.IRON, 3.0F, -2.4F));
    }

    /**
     * Logica de congelado en area al golpear el agua.
     *
     * El raytrace normal de "golpear/interactuar" de Minecraft ignora los fluidos, asi que
     * al apuntar al agua devolveria el bloque del fondo del charco. Por eso hacemos nuestro
     * propio raytrace con deteccion de fluidos, igual que la cubeta y el nenufar del vanilla
     * ({@code getPlayerPOVHitResult}): el primer agua que cruza el rayo es la mas cercana,
     * asi que no hace falta ningun bloque solido a la vista (agua profunda desde la
     * superficie congela la propia superficie).
     *
     * El rayo sale en la direccion exacta de la mirada (xRot/yRot): apuntar hacia abajo
     * congela mas abajo, apuntar hacia arriba mas arriba, sin depender de la altura del
     * jugador. El area se centra en la casilla de agua golpeada, descartando los bloques
     * de la zona prohibida del jugador (su cuerpo y la columna que sube desde su cabeza):
     * si se mira hacia arriba nadando, el hielo nunca sale pegado ni encima del
     * personaje, sino mas adelante por donde va la mirada.
     *
     * Se invoca desde dos vias: el clic izquierdo sobre un bloque (cliente y servidor) y el
     * paquete propio de golpe al aire (solo servidor, ver PaqueteGolpearAgua).
     *
     * @param xRot inclinacion vertical de la mirada (grados) con la que se golpeo
     * @param yRot rotacion horizontal de la mirada (grados) con la que se golpeo
     * @return true si se ha apuntado al agua (y el clic debe cancelarse)
     */
    public boolean intentarCongelar(final Level level, final Player player, final ItemStack stack,
                                    final float xRot, final float yRot) {
        final BlockHitResult hit = apuntarAguaFueraDelJugador(level, player, xRot, yRot);
        if (hit == null) {
            return false;
        }

        final BlockPos centro = hit.getBlockPos();
        if (!level.getBlockState(centro).is(Blocks.WATER)) {
            return false;
        }

        if (level.isClientSide()) {
            return true;
        }

        // Se elige una forma al azar para cada golpe
        final int[] patron = PATRONES_HELADA[player.getRandom().nextInt(PATRONES_HELADA.length)];
        final int ancho = patron[0];
        final int fondo = patron[1];

        // Ninguna celda del patron puede caer en la zona prohibida del jugador: su cuerpo
        // (para no congelarlo dentro) ni la columna que sube desde su cabeza
        final AABB cajaJugador = player.getBoundingBox();
        final AABB columna = columnaSobreElJugador(player);

        int congelados = 0;
        for (int i = 0; i < ancho; i++) {
            for (int j = 0; j < fondo; j++) {
                final BlockPos objetivo = centro.offset(i - ancho / 2, 0, j - fondo / 2);
                final AABB celda = new AABB(objetivo);
                if (level.getBlockState(objetivo).is(Blocks.WATER)
                        && !cajaJugador.intersects(celda)
                        && !columna.intersects(celda)
                        && level.setBlock(objetivo, Blocks.ICE.defaultBlockState(), 3)) {
                    congelados++;
                }
            }
        }

        if (congelados > 0) {
            // Cada uso consume 1 de durabilidad
            stack.hurtAndBreak(1, player, InteractionHand.MAIN_HAND);
            level.playSound(null, centro, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.6F, 1.15F);
        }

        return true;
    }

    /**
     * Raytrace con deteccion de fluidos que devuelve el bloque de agua al que se apunta,
     * saltando la zona prohibida del jugador (su cuerpo y la columna que sube desde su
     * cabeza con {@value #MARGEN_COLUMNA} de margen horizontal).
     *
     * Si no hay ningun bloque solido al alcance (agua profunda), el propio rayo contra el
     * fluido devuelve la casilla de agua mas cercana, asi que siempre hay referencia. Al
     * bucear el rayo chocaria enseguida con el agua de nuestra propia cara (el bloque que
     * nos contiene), y apuntando hacia arriba chocaria con el agua justo sobre la cabeza;
     * en ambos casos se avanza el rayo para buscar agua mas adelante por donde mira el
     * personaje, nunca encima suyo.
     *
     * @param xRot inclinacion vertical de la mirada (grados)
     * @param yRot rotacion horizontal de la mirada (grados)
     * @return el golpe contra un bloque fuera de la zona prohibida, o null si no hay agua
     */
    private static BlockHitResult apuntarAguaFueraDelJugador(final Level level, final Player player,
                                                             final float xRot, final float yRot) {
        final Vec3 direccion = player.calculateViewVector(xRot, yRot);
        Vec3 desde = player.getEyePosition();
        final Vec3 hasta = desde.add(direccion.scale(player.blockInteractionRange()));
        final AABB cajaJugador = player.getBoundingBox();
        final AABB columna = columnaSobreElJugador(player);

        for (int salto = 0; salto < MAX_SALTOS_RAYO; salto++) {
            final BlockHitResult hit = level.clip(new ClipContext(
                desde, hasta, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));

            if (hit.getType() != HitResult.Type.BLOCK) {
                return null;
            }

            final AABB cajaBloque = new AABB(hit.getBlockPos());
            if (!cajaJugador.intersects(cajaBloque) && !columna.intersects(cajaBloque)) {
                return hit;
            }

            // El bloque golpeado esta en la zona prohibida del jugador: avanzamos el rayo
            // un poco mas alla para seguir buscando agua por delante de el
            desde = hit.getLocation().add(direccion.scale(1.2));
        }

        return null;
    }

    /**
     * Columna prohibida para el hielo por encima de la cabeza del jugador: su huella
     * horizontal con {@value #MARGEN_COLUMNA} de margen, desde la altura de su cabeza
     * hacia arriba sin limite.
     *
     * El cuerpo del jugador ya lo cubre su propio {@code AABB}; esta columna adiciona lo
     * que el AABB no cubre: los bloques justo encima de la cabeza. Asi, aunque se mire
     * hacia arriba nadando, el hielo nunca aparece pegado ni encima del personaje.
     */
    private static AABB columnaSobreElJugador(final Player player) {
        final AABB cuerpo = player.getBoundingBox();
        return new AABB(
            cuerpo.minX - MARGEN_COLUMNA, cuerpo.maxY, cuerpo.minZ - MARGEN_COLUMNA,
            cuerpo.maxX + MARGEN_COLUMNA, Double.MAX_VALUE, cuerpo.maxZ + MARGEN_COLUMNA);
    }
}
