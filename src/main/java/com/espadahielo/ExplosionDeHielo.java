package com.espadahielo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * La explosion helada que comparten la Bola de Hielo del Cetro y la muerte del
 * Glaciarock: mismo efecto en los dos casos.
 *
 * Al explotar en un punto:
 *  - la explosion es de potencia {@value EspadaDeHielo#POTENCIA_EXPLOSION_BOLA} (la del
 *    ghast es 1) y rompe bloques solo si el game rule mobGriefing lo permite (igual que
 *    la bola del ghast),
 *  - deja lenta a toda criatura viva que alcance el radio (menos al dueno, para que no
 *    se perjudique a si mismo),
 *  - congela en hielo el agua que rodea el crater hasta el borde,
 *  - siembra unos cuantos mechones de fuego azul del mod en el crater (solo si la
 *    explosion puede modificar el terreno),
 *  - y suelta una nube de copos y chispas turquesa.
 *
 * Todo lo que decora el crater (radio de hielo, fuego azul y ralentizacion, cantidad de
 * particulas) va en proporcion a la potencia, asi que un coloso que muere con 3 veces la
 * potencia de la bola deja un crater de hielo 3 veces mayor y no un anillo ridiculo
 * alrededor de un boquete enorme.
 *
 * Al dueno no le hace dano la explosion (solo a el, al resto de criaturas si): se le pone
 * invulnerable durante el instante en que revienta y se le deja como estaba. Asi el coloso
 * puede morir al lado de su invocador sin llevarselo por delante.
 *
 * El fuego normal de vanilla no aparece: la explosion se llama con "no incendiar" y el
 * unico fuego que queda es el azul del mod, colocado a mano.
 */
public final class ExplosionDeHielo {
    // Chispas turquesa, el mismo color que el rayo y el aura de hielo
    private static final DustParticleOptions PARTICULA_HIELO = new DustParticleOptions(
        EspadaDeHielo.COLOR_RAYO_HIELO, 1.0F);

    private ExplosionDeHielo() {
    }

    /**
     * Explota en el punto indicado con la potencia de la bola de hielo (la de siempre)
     *
     * @param servidor nivel donde explota (solo servidor: en cliente no hace nada)
     * @param autor    quien provoca la explosion (la bola que la lanza...). Sirve para
     *                 firmar el dano y para el game rule de griefing
     * @param centro   punto de la explosion
     * @param dueno    el jugador dueño de la bola o del elemental (puede no haber uno);
     *                 ni la explosion ni la ralentizacion ni el hielo le afectan a el
     */
    public static void explotar(final ServerLevel servidor, final Entity autor, final Vec3 centro,
                                final Entity dueno) {
        explotar(servidor, autor, centro, dueno, EspadaDeHielo.POTENCIA_EXPLOSION_BOLA);
    }

    /**
     * Igual que {@link #explotar(ServerLevel, Entity, Vec3, Entity)} pero con la potencia
     * que se le pase. La usa la muerte del Glaciarock, que revienta 3 veces mas fuerte que
     * la bola de hielo
     *
     * @param potencia fuerza de la explosion (la de la bola es
     *                 {@value EspadaDeHielo#POTENCIA_EXPLOSION_BOLA}; la del ghast, 1)
     */
    public static void explotar(final ServerLevel servidor, final Entity autor, final Vec3 centro,
                                final Entity dueno, final float potencia) {
        // El mobGriefing decide si la explosion abre crater y si deja fuego
        final boolean rompeBloques = net.minecraftforge.event.ForgeEventFactory
            .getMobGriefingEvent(servidor, dueno);

        // Al dueno no le toca el golpe de la explosion: se le pone invulnerable solo
        // mientras dura (explode reparte el dano de forma sincrona, dentro de la llamada) y
        // se le devuelve su estado anterior. Asi el coloso revienta al lado de su invocador
        // sin matarlo. En el cliente no hace falta el flag: el dano lo calcula el servidor
        final boolean duenoEraInvulnerable = dueno != null && dueno.isInvulnerable();
        if (dueno != null && !duenoEraInvulnerable) {
            dueno.setInvulnerable(true);
        }

        try {
            // Ojo: el boolean de explode NO es "romper bloques", es "generar fuego". Va en
            // false para que vanilla no siembre fuego normal en el crater (romper bloques ya
            // lo decide la interaccion MOB con el mobGriefing). El fuego que queda es el azul
            // del mod, que se coloca a mano justo debajo
            servidor.explode(autor, centro.x, centro.y, centro.z,
                potencia, false, Level.ExplosionInteraction.MOB);
        } finally {
            if (dueno != null && !duenoEraInvulnerable) {
                dueno.setInvulnerable(false);
            }
        }

        // Todo lo demas va en proporcion a la potencia (con la bola, factor 1)
        final float escala = potencia / (float) EspadaDeHielo.POTENCIA_EXPLOSION_BOLA;

        // Ralentizacion por la explosion (radio = potencia x 2, la medida de la explosion)
        aplicarRalentizacion(servidor, centro, dueno, potencia);

        // El agua alrededor pasa a hielo
        congelarAgua(servidor, centro, dueno, potencia);

        // Mechones de fuego azul en el crater (bloque propio del mod)
        if (rompeBloques) {
            sembrarFuegoAzul(servidor, centro, potencia);
        }

        // Aurora helada de la explosion: copos y chispas turquesa, mas cuantos mas grandes
        // sea el estruendo
        final int copos = Math.round(20 * escala);
        final double dispersion = 1.2 * escala;
        servidor.sendParticles(ParticleTypes.SNOWFLAKE,
            centro.x, centro.y + 0.5, centro.z, copos,
            dispersion, dispersion, dispersion, 0.05);
        servidor.sendParticles(PARTICULA_HIELO,
            centro.x, centro.y + 0.5, centro.z, Math.max(1, Math.round(12 * escala)),
            dispersion, dispersion, dispersion, 0.02);
    }

    /**
     * Deja lento a todo lo que este dentro del radio de la explosion (potencia x 2),
     * con el mismo efecto que la Espada de Hielo. Al dueno no le afecta
     */
    private static void aplicarRalentizacion(final ServerLevel servidor, final Vec3 centro,
                                             final Entity dueno, final float potencia) {
        final double radio = potencia * 2.0;
        final double radioCuadrado = radio * radio;
        final AABB caja = new AABB(centro, centro).inflate(radio + 0.25);

        for (final LivingEntity enemigo : servidor.getEntitiesOfClass(LivingEntity.class, caja,
                e -> e.isAlive() && e != dueno)) {
            if (enemigo.distanceToSqr(centro.x, centro.y, centro.z) <= radioCuadrado) {
                enemigo.addEffect(new MobEffectInstance(
                    MobEffects.SLOWNESS,
                    EspadaDeHielo.TICKS_RALENTIZACION,
                    EspadaDeHielo.NIVEL_RALENTIZACION));
            }
        }
    }

    /**
     * Congela en area el agua que rodea el punto de impacto: todos los bloques de agua
     * dentro de un radio de {@value EspadaDeHielo#RADIO_CONGELADO_BOLA} bloques pasan a
     * hielo (ese radio crece con la potencia de la explosion). Como la explosion no
     * destruye el agua, esto se hace justo despues de explotar, para pillar lo que quede
     * alrededor del crater. Si congela algo, suena el cristal de la espada
     */
    private static void congelarAgua(final ServerLevel servidor, final Vec3 centro,
                                     final Entity dueno, final float potencia) {
        final int radio = Math.max(1, Math.round(EspadaDeHielo.RADIO_CONGELADO_BOLA
            * potencia / EspadaDeHielo.POTENCIA_EXPLOSION_BOLA));
        final double radioCuadrado = radio * radio;
        final BlockPos centroBloque = BlockPos.containing(centro);
        final AABB cajaDueno = (dueno == null) ? null : dueno.getBoundingBox();
        int congelados = 0;

        for (final BlockPos pos : BlockPos.betweenClosed(
                centroBloque.offset(-radio, -radio, -radio), centroBloque.offset(radio, radio, radio))) {
            if (cajaDueno != null && cajaDueno.intersects(new AABB(pos))) {
                continue;
            }
            if (pos.distToCenterSqr(centro) <= radioCuadrado
                    && servidor.getBlockState(pos).is(Blocks.WATER)
                    && servidor.setBlock(pos, Blocks.ICE.defaultBlockState(), 3)) {
                congelados++;
            }
        }

        if (congelados > 0) {
            servidor.playSound(null, centro.x, centro.y, centro.z,
                SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.7F, 1.15F);
        }
    }

    /**
     * Deja mechones de fuego azul cielo ({@link FuegoAzulBlock}) repartidos por el crater
     * de la explosion, en un radio y una cantidad proporcionales a la potencia. A
     * diferencia del fuego de almas, este bloque no necesita una base de arena/tierra de
     * almas: se mantiene sobre cualquier suelo firme del crater, asi que no hay que generar
     * nada debajo. Si coloca alguno, suena el escape de alma
     */
    private static void sembrarFuegoAzul(final ServerLevel servidor, final Vec3 centro,
                                         final float potencia) {
        final var azar = servidor.getRandom();
        final float escala = potencia / (float) EspadaDeHielo.POTENCIA_EXPLOSION_BOLA;
        final int radio = Math.max(1, Math.round(EspadaDeHielo.RADIO_FUEGOS_AZULES * escala));
        final int maxFuegos = Math.max(1, Math.round(EspadaDeHielo.MAX_FUEGOS_AZULES * escala));
        final double radioCuadrado = radio * radio;
        final BlockPos centroBloque = BlockPos.containing(centro);
        final List<BlockPos> candidatos = new ArrayList<>();

        // Huecos de aire con suelo firme debajo: candidatos a quedarse con fuego
        // (sin fluidos: ni agua ni lava cuentan como suelo)
        for (final BlockPos pos : BlockPos.betweenClosed(
                centroBloque.offset(-radio, -radio, -radio), centroBloque.offset(radio, radio, radio))) {
            final var suelo = servidor.getBlockState(pos.below());
            if (pos.distToCenterSqr(centro) <= radioCuadrado
                    && servidor.getBlockState(pos).isAir()
                    && !suelo.isAir() && suelo.getFluidState().isEmpty()) {
                candidatos.add(pos.immutable());
            }
        }

        int colocados = 0;
        while (!candidatos.isEmpty() && colocados < maxFuegos) {
            final BlockPos pos = candidatos.remove(azar.nextInt(candidatos.size()));
            servidor.setBlock(pos, EspadaDeHielo.FUEGO_AZUL.get().defaultBlockState(), 3);
            colocados++;
        }

        if (colocados > 0) {
            servidor.playSound(null, centro.x, centro.y, centro.z,
                SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 0.6F, 1.0F);
        }
    }
}
