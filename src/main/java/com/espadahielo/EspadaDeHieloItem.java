package com.espadahielo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

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
 *  - Al usar con clic derecho lanza un rayo de hielo en la direccion de la mirada: dibuja
 *    la linea hasta donde se apunta (hasta {@value EspadaDeHielo#ALCANCE_RAYO_HIELO}
 *    bloques) y congela al enemigo que alcance.
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

    // Particula de la linea del rayo: puntos de turquesa claro. Con este tipo de particula
    // elegimos el color exacto y, con el tamano, su duracion (asi la linea se desvanece al
    // cabo de mas o menos un segundo, en vez de quedarse como las de "End Rod", > 3 segundos)
    private static final DustParticleOptions PARTICULA_RAYO = new DustParticleOptions(
        EspadaDeHielo.COLOR_RAYO_HIELO, EspadaDeHielo.TAMANO_PARTICULA_RAYO);

    // Particula de la aurora que envuelve al enemigo congelado (azul claro)
    private static final DustParticleOptions PARTICULA_AURA = new DustParticleOptions(
        EspadaDeHielo.COLOR_AURA_HIELO, 0.9F);

    // Margen horizontal de la columna prohibida sobre el jugador: el hielo jamas debe
    // aparecer dentro de su cuerpo ni en su columna superior, ni siquiera mirando arriba
    private static final double MARGEN_COLUMNA = 0.5;

    public EspadaDeHieloItem(final Item.Properties properties) {
        // Mismas propiedades que una espada de hierro: durabilidad, reparacion con hierro,
        // encantabilidad, reglas de herramienta y atributos de ataque identicos
        super(properties.sword(ToolMaterial.IRON, 3.0F, -2.4F));
    }

    /**
     * Clic derecho: lanza el rayo de hielo hacia donde mira el jugador. Si se mantiene pulsado
     * el boton, {@link #onUseTick} sigue disparando (uno cada
     * {@value EspadaDeHielo#TICKS_ENTRE_DISPAROS_RAYO} ticks).
     *
     * Minecraft llama a este metodo tanto en el cliente como en el servidor. Aqui solo se
     * empieza a "usar" el objeto ({@code startUsingItem}); quien modifica el mundo y envia las
     * particulas es el servidor. El primer disparo sale ya en este metodo, para que un clic
     * rapido (pulsar y soltar enseguida) tambien dispare. Cada disparo consume 1 de durabilidad.
     */
    @Override
    public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
        // Arranca el "uso" del objeto para que, mientras no se suelte el boton, se siga
        // llamando a onUseTick. Sin animacion (ver getUseAnimation)
        player.startUsingItem(hand);

        if (!level.isClientSide()) {
            lanzarRayoDeHielo(level, player, true, true);
            player.getItemInHand(hand).hurtAndBreak(1, player, hand); // 1 de durabilidad
        }

        return InteractionResult.CONSUME;
    }

    // El rayo no tiene tope de tiempo: se puede mantener pulsado tanto como se quiera
    @Override
    public int getUseDuration(final ItemStack stack, final LivingEntity living) {
        return APPROXIMATELY_INFINITE_USE_DURATION;
    }

    // Sin animacion de uso: no queremos que la espada se ponga en pose de arco o de comer
    @Override
    public ItemUseAnimation getUseAnimation(final ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    /**
     * Se ejecuta cada tick mientras el jugador mantiene pulsado el clic derecho.
     *
     * La LINEA del rayo se vuelve a dibujar cada {@value EspadaDeHielo#TICKS_DIBUJO_RAYO}
     * ticks, para que se vea siempre (continua) mientras se mantiene pulsado. En cambio, el
     * congelado, el sonido y el gasto de durabilidad se aplican cada
     * {@value EspadaDeHielo#TICKS_ENTRE_DISPAROS_RAYO} ticks, y el dano cada
     * {@value EspadaDeHielo#TICKS_DANIO_RAYO} ticks (un segundo).
     *
     * Solo trabaja el servidor: el cliente se limita a mantener el gesto y a mover la vista, y
     * como el rayo se recalcula con la mirada actual, se puede ir girando mientras dispara.
     */
    @Override
    public void onUseTick(final Level level, final LivingEntity living, final ItemStack stack,
                          final int remainingUseTicks) {
        if (level.isClientSide() || !(living instanceof Player player)) {
            return;
        }

        final int transcurridos = getUseDuration(stack, living) - remainingUseTicks;
        if (transcurridos <= 0 || transcurridos % EspadaDeHielo.TICKS_DIBUJO_RAYO != 0) {
            return;
        }

        // El disparo de "el primer tick" ya lo hace use(), asi que aqui solo contamos a partir de el
        final boolean disparo = transcurridos % EspadaDeHielo.TICKS_ENTRE_DISPAROS_RAYO == 0;
        final boolean conDanio = transcurridos % EspadaDeHielo.TICKS_DANIO_RAYO == 0;

        lanzarRayoDeHielo(level, player, disparo, disparo && conDanio);
        if (disparo) {
            stack.hurtAndBreak(1, player, living.getUsedItemHand()); // 1 de durabilidad por disparo
        }
    }

    /**
     * Traza el rayo desde los ojos del jugador en la direccion de su mirada, dibuja la linea
     * y congela al primer enemigo que encuentra antes que un bloque solido (o nada, si solo
     * hay aire, en cuyo caso el rayo llega hasta el alcance maximo).
     *
     * @param aplicarEfectos true para congelar al enemigo alcanzado en este disparo (hielo +
     *                       Ralentizacion), sonar y gastar durabilidad
     * @param aplicarDanio   true para ademas hacerle dano (se usa para que, manteniendo el
     *                       rayo sobre el mismo objetivo, le caiga dano cada
     *                       {@value EspadaDeHielo#TICKS_DANIO_RAYO} ticks, o sea cada segundo)
     */
    private static void lanzarRayoDeHielo(final Level level, final Player player,
                                          final boolean aplicarEfectos, final boolean aplicarDanio) {
        final Vec3 origen = player.getEyePosition();
        final Vec3 direccion = player.getLookAngle();
        final Vec3 alcance = origen.add(direccion.scale(EspadaDeHielo.ALCANCE_RAYO_HIELO));

        // Punto donde se apunta: el primer bloque solido, o el alcance maximo si no hay nada
        // (el agua no detiene el rayo; es un rayo de hielo, no le afecta)
        final BlockHitResult golpeBloque = level.clip(new ClipContext(
            origen, alcance, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 fin = golpeBloque.getType() == HitResult.Type.BLOCK ? golpeBloque.getLocation() : alcance;

        // Primer enemigo atravesado por el rayo, siempre que este antes que el bloque
        final EntityHitResult golpeEntidad = buscarObjetivo(level, player, origen, fin);
        LivingEntity objetivo = null;
        if (golpeEntidad != null && golpeEntidad.getEntity() instanceof LivingEntity vivo) {
            fin = golpeEntidad.getLocation();
            objetivo = vivo;
        }

        // Congelado: hielo (ticks congelado, que dejan al enemigo helado) mas Ralentizacion,
        // para que ademas se mueva mucho mas despacio. Solo en los fps de "disparo"
        if (aplicarEfectos && objetivo != null && objetivo.canFreeze()) {
            objetivo.setTicksFrozen(Math.max(objetivo.getTicksFrozen(), EspadaDeHielo.TICKS_CONGELADO_RAYO));
            objetivo.addEffect(new MobEffectInstance(
                MobEffects.SLOWNESS,
                EspadaDeHielo.TICKS_RALENTIZACION,
                EspadaDeHielo.NIVEL_RALENTIZACION
            ));
            level.playSound(null, objetivo.getX(), objetivo.getEyeY(), objetivo.getZ(),
                SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 0.9F, 0.8F);

            // Dano del rayo: solo en los disparos marcados (uno por segundo), no en todos
            if (aplicarDanio && level instanceof ServerLevel server) {
                objetivo.hurtServer(server, level.damageSources().freeze(), EspadaDeHielo.DANIO_RAYO_HIELO);
            }
        }

        // La linea visual (en cada tick de dibujo) y la aurora de hielo en los disparos
        dibujarRayo(level, origen, fin);
        if (aplicarEfectos && objetivo != null && level instanceof ServerLevel server) {
            dibujarAuraCongelada(server, objetivo);
        }
        if (aplicarEfectos) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 0.7F, 1.4F);
        }
    }

    /**
     * Devuelve el enemigo mas cercano que corta el segmento {@code origen-fin}, o null si no
     * hay ninguno. Se prueba la caja de cada entidad viva con un margen para que no cueste
     * acertar a un enemigo pequeno o en movimiento.
     */
    private static EntityHitResult buscarObjetivo(final Level level, final Player player,
                                                  final Vec3 origen, final Vec3 fin) {
        final AABB caja = new AABB(origen, fin).inflate(1.0);

        Entity mejor = null;
        Vec3 mejorPunto = null;
        double mejorDistancia = Double.MAX_VALUE;

        for (final Entity entidad : level.getEntities(player, caja, EspadaDeHieloItem::esObjetivoValido)) {
            final Optional<Vec3> punto = entidad.getBoundingBox().inflate(0.3).clip(origen, fin);
            if (punto.isPresent()) {
                final double distancia = origen.distanceToSqr(punto.get());
                if (distancia < mejorDistancia) {
                    mejorDistancia = distancia;
                    mejor = entidad;
                    mejorPunto = punto.get();
                }
            }
        }

        return mejor == null ? null : new EntityHitResult(mejor, mejorPunto);
    }

    // Solo se apunta a criaturas vivas: se descartan las que ya estan muertas, los
    // espectadores y lo que no se puede golpear (flechas, cuadros de pintura, etc.)
    private static boolean esObjetivoValido(final Entity entidad) {
        return entidad instanceof LivingEntity && entidad.isAlive()
            && !entidad.isSpectator() && entidad.isPickable();
    }

    /**
     * Dibuja el rayo como una linea fina de particulas desde {@code origen} hasta {@code fin}.
     * Las envia el servidor, que se encarga de repartirlas entre los jugadores cercanos.
     */
    private static void dibujarRayo(final Level level, final Vec3 origen, final Vec3 fin) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }

        final Vec3 tramo = fin.subtract(origen);
        final double longitud = tramo.length();
        if (longitud < 1.0E-4) {
            return;
        }

        final Vec3 paso = tramo.normalize().scale(EspadaDeHielo.PASO_PARTICULAS_RAYO);
        final int puntos = (int) Math.ceil(longitud / EspadaDeHielo.PASO_PARTICULAS_RAYO);

        for (int i = 0; i <= puntos; i++) {
            final Vec3 punto = origen.add(paso.scale(i));
            // Puntos turquesa claro: el color y el tamano estan fijados en PARTICULA_RAYO
            server.sendParticles(PARTICULA_RAYO, punto.x, punto.y, punto.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * Dibuja una pequena aurora de hielo alrededor del enemigo alcanzado: copos de nieve y
     * chispas azul claro repartidos por su caja, para que se note que esta helado.
     *
     * Minecraft solo pinta el tinte azul de congelado en la pantalla del jugador (HUD), no en
     * el cuerpo de las criaturas: a una criatura congelada solo se le nota el temblor. Esta
     * aurora es la que le da el aspecto helado visible desde fuera.
     */
    private static void dibujarAuraCongelada(final ServerLevel server, final LivingEntity objetivo) {
        final AABB caja = objetivo.getBoundingBox().inflate(0.15);
        final RandomSource azar = objetivo.getRandom();

        for (int i = 0; i < 10; i++) {
            final double x = caja.minX + azar.nextDouble() * (caja.maxX - caja.minX);
            final double y = caja.minY + azar.nextDouble() * (caja.maxY - caja.minY);
            final double z = caja.minZ + azar.nextDouble() * (caja.maxZ - caja.minZ);
            server.sendParticles(ParticleTypes.SNOWFLAKE, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }

        for (int i = 0; i < 6; i++) {
            final double x = caja.minX + azar.nextDouble() * (caja.maxX - caja.minX);
            final double y = caja.minY + azar.nextDouble() * (caja.maxY - caja.minY);
            final double z = caja.minZ + azar.nextDouble() * (caja.maxZ - caja.minZ);
            server.sendParticles(PARTICULA_AURA, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
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
