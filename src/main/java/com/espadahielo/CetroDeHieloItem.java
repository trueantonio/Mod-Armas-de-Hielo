package com.espadahielo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
 *    rompe bloques. Cada bola consume 1 de durabilidad, igual que una flecha, y entre
 *    bola y bola hay que esperar medio segundo ({@link EspadaDeHielo#TICKS_ENTRE_DISPAROS_CETRO}),
 *    para que clicar muy rapido no llene el aire de proyectiles.
 *  - Clic derecho: invocacion del elemental de hielo tras un casteo de 3 segundos
 *    ({@link EspadaDeHielo#TICKS_CONJURO_GLACIAROCK}). El casteo se paga con
 *    {@link EspadaDeHielo#DURABILIDAD_CONJURO_GLACIAROCK} de durabilidad, pero solo si
 *    el elemental llega a salir: si se suelta el boton antes de tiempo, si te pegan o si
 *    te vas al aire o al agua, no cuesta nada. Invocar no destruye nunca el cetro: si el
 *    cobro lo dejaria a 0 (castear con justo esas 165), se queda con 1. No se puede
 *    castear en el aire ni nadando, y con el cetro sin energia suficiente el conjuro ni
 *    empieza (ver
 *    {@link EspadaDeHielo#MENSAJE_ENERGIA_INSUFICIENTE}). El elemental aparece delante de
 *    la mirada y, si ahi no cabe, en el primer hueco libre alrededor del jugador; sin
 *    hueco no se invoca nada ({@link EspadaDeHielo#MENSAJE_SIN_ESPACIO}). Solo puede
 *    haber un Glaciarock por invocador: al invocar otro, el anterior desaparece.
 */
public class CetroDeHieloItem extends Item {
    public CetroDeHieloItem(final Item.Properties properties) {
        super(properties);
    }

    /**
     * Lanza una bola de hielo en la direccion (xRot, yRot) apuntada y consume 1 de
     * durabilidad del cetro. Solo dispara si el cetro no esta en enfriamiento: entre bola
     * y bola tienen que pasar {@link EspadaDeHielo#TICKS_ENTRE_DISPAROS_CETRO} ticks (medio
     * segundo), que es lo que evita que clicar muy rapido lance varias de golpe.
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
        final ItemStack cetro = player.getMainHandItem();

        // Cadencia: hasta que no pase medio segundo (TICKS_ENTRE_DISPAROS_CETRO) desde la
        // bola anterior no sale otra. El enfriamiento lo lleva el juego por jugador y por
        // item, y ademas dibuja el relojito encima del cetro en el HUD. Se comprueba en los
        // dos lados: el servidor es el que manda, y el cliente (que recibe el enfriamiento
        // del servidor) no llega ni a pedirlo
        if (player.getCooldowns().isOnCooldown(cetro)) {
            // El clic se cancela igual (el cetro no sirve para minar), pero no sale bola
            return true;
        }

        if (level.isClientSide()) {
            // El cliente solo cancela el clic; la bola la crea el servidor
            return true;
        }

        final Vec3 direccion = player.calculateViewVector(xRot, yRot);
        level.addFreshEntity(new BolaDeHielo(level, player, direccion));

        // Arrancar la cuenta de enfriamiento (0,5 s) hasta la siguiente bola
        player.getCooldowns().addCooldown(cetro, EspadaDeHielo.TICKS_ENTRE_DISPAROS_CETRO);

        // Sonido de disparo: la misma carga de fuego que la bola del ghast, algo mas aguda
        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(),
            SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.7F, 1.3F);

        // Cada bola consume 1 de durabilidad, igual que una flecha
        cetro.hurtAndBreak(1, player, InteractionHand.MAIN_HAND);

        return true;
    }

    // --- Invocacion del Glaciarock (clic derecho) ---

    /**
     * Empieza el conjuro: se mantiene pulsado el clic derecho durante
     * {@link EspadaDeHielo#TICKS_CONJURO_GLACIAROCK} ticks (3 segundos) y, si se aguanta,
     * el elemental aparece al soltar (ver {@link #finishUsingItem}).
     *
     * Antes de empezar hay dos pegas: hay que estar de pie en el suelo (ni en el aire ni
     * nadando: durante el casteo tambien se cancelaria, ver {@link #onUseTick}) y el cetro
     * tiene que tener las {@link EspadaDeHielo#DURABILIDAD_CONJURO_GLACIAROCK} de energia
     * que cuesta la invocacion. Si no las tiene, ni empieza y se avisa al jugador.
     */
    @Override
    public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
        // El conjuro es cosa de la mano que lleva el cetro
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        if (!sePuedeEmpezarElConjuro(player)) {
            return InteractionResult.FAIL;
        }

        final ItemStack cetro = player.getItemInHand(hand);

        // La energia se comprueba al empezar (para no hacer esperar 3 segundos para nada)
        // y otra vez al terminar, por si el cetro se ha estropeado por el camino
        if (!tieneEnergiaSuficiente(player, cetro)) {
            avisarAlJugador(level, player, EspadaDeHielo.MENSAJE_ENERGIA_INSUFICIENTE);
            return InteractionResult.FAIL;
        }

        player.startUsingItem(hand);

        if (!level.isClientSide()) {
            // Sonido de invocacion: el "preparate" del evoker, mas grave para un coloso
            level.playSound(null, player.getX(), player.getEyeY(), player.getZ(),
                SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.PLAYERS, 0.8F, 0.7F);

            // Primer gesto del casteo (los siguientes los lanza onUseTick)
            player.swing(hand, true);
        }

        // CONSUME: el clic derecho queda gastado en el conjuro (no mina, no usa bloques)
        return InteractionResult.CONSUME;
    }

    /**
     * De la duracion del conjuro se encarga el propio juego a traves del uso del item:
     * tantos ticks como dure el casteo
     */
    @Override
    public int getUseDuration(final ItemStack cetro, final LivingEntity usuario) {
        return EspadaDeHielo.TICKS_CONJURO_GLACIAROCK;
    }

    /**
     * Cada tick que aguanta el conjuro: suelta las particulas de hielo en la punta del
     * cetro, gesticula con el brazo de vez en cuando y comprueba que no se haya roto el
     * casteo (dano, aire o agua), en cuyo caso lo cancela sin cobrar nada.
     *
     * Todo esto es cosa del servidor: en el cliente el estado de "esta usando el item" va
     * sincronizado desde el servidor, asi que la barra de casteo (ver ClienteMod) se apaga
     * sola en cuanto el servidor cancela o termina
     */
    @Override
    public void onUseTick(final Level level, final LivingEntity entidad, final ItemStack cetro,
                          final int ticksRestantes) {
        if (level.isClientSide() || !(entidad instanceof Player jugador)) {
            return;
        }

        if (estaRotoElConjuro(jugador)) {
            jugador.stopUsingItem();
            return;
        }

        // Particulas de hielo en la punta del cetro (donde sale la bola), como si el cetro
        // fuese cargandose de escarcha
        final Vec3 punta = BolaDeHielo.puntoDeSalidaDelCetro(level, jugador,
            jugador.calculateViewVector(jugador.getXRot(), jugador.getYRot()));
        final ServerLevel servidor = (ServerLevel) level;
        servidor.sendParticles(ParticleTypes.SNOWFLAKE, punta.x, punta.y, punta.z,
            2, 0.10, 0.10, 0.10, 0.01);
        servidor.sendParticles(new DustParticleOptions(EspadaDeHielo.COLOR_RAYO_HIELO, 0.9F),
            punta.x, punta.y, punta.z, 1, 0.06, 0.06, 0.06, 0.0);

        // Gestos del casteo: ademas del inicial, uno cada TICKS_ENTRE_GESTOS_CONJURO_GLACIAROCK
        final int transcurridos = EspadaDeHielo.TICKS_CONJURO_GLACIAROCK - ticksRestantes;
        if (transcurridos > 0
                && transcurridos % EspadaDeHielo.TICKS_ENTRE_GESTOS_CONJURO_GLACIAROCK == 0) {
            jugador.swing(InteractionHand.MAIN_HAND, true);
        }
    }

    /**
     * Suelta el clic derecho antes de tiempo: el conjuro no cuenta para nada (no se paga
     * durabilidad ni sale el elemental). No hay nada que deshacer, porque el progreso lo
     * lleva el propio uso del item; al soltar, el juego deja de contar y no llega a
     * llamar a {@link #finishUsingItem}
     */
    @Override
    public boolean releaseUsing(final ItemStack cetro, final Level level, final LivingEntity entidad,
                                final int ticksRestantes) {
        return false;
    }

    /**
     * El conjuro ha aguantado los 3 segundos: toca invocar al Glaciarock. Si no cabe en
     * ningun sitio (ni delante ni alrededor) se avisa y no se cobra nada. La durabilidad
     * se descuenta solo cuando el elemental ya esta en el mundo
     */
    @Override
    public ItemStack finishUsingItem(final ItemStack cetro, final Level level,
                                     final LivingEntity entidad) {
        if (level.isClientSide() || !(entidad instanceof Player jugador)) {
            return cetro;
        }

        if (!tieneEnergiaSuficiente(jugador, cetro)) {
            avisarAlJugador(level, jugador, EspadaDeHielo.MENSAJE_ENERGIA_INSUFICIENTE);
            return cetro;
        }

        if (!conjurarGlaciarock((ServerLevel) level, jugador, cetro)) {
            avisarAlJugador(level, jugador, EspadaDeHielo.MENSAJE_SIN_ESPACIO);
        }

        return cetro;
    }

    /**
     * Invoca al elemental delante del jugador (o donde haya hueco, ver
     * {@link #buscarSitioDeInvocacion}), le pone al jugador como dueno y descuenta la
     * energia del conjuro. El cobro nunca deja el cetro a 0: si el golpe de durabilidad lo
     * fuese a destruir (castear con justo las {@link EspadaDeHielo#DURABILIDAD_CONJURO_GLACIAROCK}),
     * se cobra una menos y el cetro se queda con 1. De paso, si el jugador ya tenia otro
     * Glaciarock, lo sustituye (desaparece sin explosion: no es una muerte)
     *
     * @return true si el elemental ha llegado a salir al mundo
     */
    private static boolean conjurarGlaciarock(final ServerLevel servidor, final Player jugador,
                                              final ItemStack cetro) {
        final Glaciarock elemental = new Glaciarock(EspadaDeHielo.ENTIDAD_GLACIAROCK.get(), servidor);

        final Vec3 sitio = buscarSitioDeInvocacion(servidor, jugador, elemental);
        if (sitio == null) {
            return false;
        }

        // Un solo elemental por invocador: el anterior se va sin mas (no se puede
        // desinvocar a mano, pero si sustituir invocando otro)
        for (final Glaciarock anterior : servidor.getEntitiesOfClass(Glaciarock.class,
                jugador.getBoundingBox().inflate(EspadaDeHielo.RADIO_REEMPLAZO_GLACIAROCK),
                candidato -> candidato.isAlive() && candidato.isOwnedBy(jugador))) {
            anterior.discard();
        }

        elemental.snapTo(sitio.x, sitio.y, sitio.z, jugador.getYRot(), 0.0F);
        // Directamente domesticado y con dueno (sin corazones ni celebraciones: esto es
        // una invocacion, no un lobo domesticado) y sin despawnear por estar lejos
        elemental.setTame(true, true);
        elemental.setOwner(jugador);
        elemental.setPersistenceRequired();
        servidor.addFreshEntity(elemental);

        // Ahora si: la energia se cobra cuando la invocacion ha salido bien
        if (!jugador.hasInfiniteMaterials()) {
            // El cetro no se destruye nunca por invocar: si el cobro lo dejaria a 0 (pasa
            // al castear con justo las DURABILIDAD_CONJURO_GLACIAROCK), se cobra una menos
            // y el cetro se queda con 1 de durabilidad
            final int restante = cetro.getMaxDamage() - cetro.getDamageValue();
            final int gasto = Math.min(EspadaDeHielo.DURABILIDAD_CONJURO_GLACIAROCK, restante - 1);
            if (gasto > 0) {
                cetro.hurtAndBreak(gasto, jugador, InteractionHand.MAIN_HAND);
            }
        }

        // Festejo de la invocacion: crujido de hielo, el conjuro del evoker y una ventisca
        // de copos alrededor del coloso entero
        servidor.playSound(null, elemental.getX(), elemental.getY(), elemental.getZ(),
            SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.5F, 0.5F);
        servidor.playSound(null, elemental.getX(), elemental.getY(), elemental.getZ(),
            SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 0.6F);
        servidor.sendParticles(ParticleTypes.SNOWFLAKE, elemental.getX(), elemental.getY() + 0.5,
            elemental.getZ(), 90, 2.0, 0.5, 2.0, 0.06);
        servidor.sendParticles(new DustParticleOptions(EspadaDeHielo.COLOR_RAYO_HIELO, 1.6F),
            elemental.getX(), elemental.getY() + 3.6, elemental.getZ(), 60, 2.0, 3.4, 2.0, 0.02);

        return true;
    }

    /**
     * Busca el hueco donde meter al elemental: primero delante de la mirada (el lado 0 del
     * primer anillo), y si ahi no cabe, anillos completos alrededor del jugador a cada
     * distancia de {@link EspadaDeHielo#RADIOS_INVOCACION_GLACIAROCK}.
     *
     * Un sitio vale si la caja entera del coloso (4 x 7.25 x 4) cabe sin chocar con nada,
     * no pisa al jugador y el coloso no acaba metido en agua. Se prueba siempre a la altura
     * de los pies del jugador
     *
     * @return la posicion elegida, o null si no hay hueco por ningun lado
     */
    private static Vec3 buscarSitioDeInvocacion(final ServerLevel servidor, final Player jugador,
                                                final Glaciarock elemental) {
        final float yaw = jugador.getYRot() * ((float) Math.PI / 180.0F);
        final double frenteX = -Math.sin(yaw);
        final double frenteZ = Math.cos(yaw);

        for (final double radio : EspadaDeHielo.RADIOS_INVOCACION_GLACIAROCK) {
            for (int lado = 0; lado < EspadaDeHielo.LADOS_INVOCACION_GLACIAROCK; lado++) {
                final double angulo = (Math.PI * 2.0 * lado) / EspadaDeHielo.LADOS_INVOCACION_GLACIAROCK;
                final double coseno = Math.cos(angulo);
                final double seno = Math.sin(angulo);

                // Girar el vector de la mirada alrededor del eje vertical: lado 0 = delante
                final double x = jugador.getX() + (frenteX * coseno - frenteZ * seno) * radio;
                final double z = jugador.getZ() + (frenteX * seno + frenteZ * coseno) * radio;

                elemental.snapTo(x, jugador.getY(), z, jugador.getYRot(), 0.0F);

                if (!servidor.noCollision(elemental)
                        || elemental.getBoundingBox().intersects(jugador.getBoundingBox())
                        || !servidor.getFluidState(BlockPos.containing(elemental.position())).isEmpty()) {
                    continue;
                }

                return elemental.position();
            }
        }

        return null;
    }

    // Se puede empezar el conjuro de pie y en seco (ni en el aire ni nadando)
    private static boolean sePuedeEmpezarElConjuro(final Player jugador) {
        return jugador.onGround() && !jugador.isInWater() && !jugador.isSwimming();
    }

    // Motivos por los que un casteo en marcha se echa atras: que te peguen (hurtTime > 0)
    // o que te vayas al aire o al agua. Moverse por el suelo no lo cancela
    private static boolean estaRotoElConjuro(final Player jugador) {
        return jugador.hurtTime > 0
            || !jugador.onGround()
            || jugador.isInWater()
            || jugador.isSwimming();
    }

    // Energia del cetro: las 165 de la invocacion tienen que seguir ahi. En creativo la
    // durabilidad no se gasta, asi que tampoco se exige
    private static boolean tieneEnergiaSuficiente(final Player jugador, final ItemStack cetro) {
        if (jugador.hasInfiniteMaterials()) {
            return true;
        }

        return cetro.getMaxDamage() - cetro.getDamageValue()
            >= EspadaDeHielo.DURABILIDAD_CONJURO_GLACIAROCK;
    }

    // Aviso al jugador por encima de la barra del hotbar (donde se dibuja la barra de
    // casteo, ver ClienteMod). Solo servidor: es quien manda los mensajes
    private static void avisarAlJugador(final Level level, final Player jugador, final String clave) {
        if (level.isClientSide()) {
            return;
        }

        if (jugador instanceof ServerPlayer jugadorServidor) {
            jugadorServidor.sendSystemMessage(Component.translatable(clave), true);
        }
    }
}
