package com.espadahielo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Bola de hielo lanzada por el Cetro de Hielo.
 *
 * Se comporta como la bola de fuego del ghast (subclase de Fireball: misma fisica de
 * proyectil "hurting", explosion al tocar bloque o entidad) pero con estos cambios:
 *  - La explosion es mas potente ({@value EspadaDeHielo#POTENCIA_EXPLOSION_BOLA} en vez
 *    de 1) y sigue respetando la regla de game rule de romper bloques (mobGriefing).
 *    La propia explosion deja lento a todo lo que alcanza (salvo al dueno).
 *  - En cuanto el proyectil toca el agua (entra en su bloque de superficie), revienta
 *    ahi mismo en vez de seguir volando; el agua que rodea el impacto se congela en
 *    area hasta el borde del crater.
 *  - El impacto directo congela al enemigo ademas de hacerle dano, y le aplica el mismo
 *    efecto de Ralentizacion que la Espada de Hielo.
 *  - El crater queda sembrado de unos cuantos mechones de fuego azul cielo (bloque
 *    propio del mod, ver {@link FuegoAzulBlock}) que ralentiza a quien lo toca (solo si
 *    la explosion puede modificar el terreno).
 *  - El rastro son copos/chispas turquesa en vez de humo, no se enciende en el aire
 *    y su aspecto es el del item "bola_de_hielo" (textura turquesa) gracias a que
 *    Fireball es ItemSupplier y el renderizador del mod usa ThrownItemRenderer.
 *  - Sale de la punta del cetro (desplazada a la derecha, a la altura de la mano y hacia
 *    delante de los ojos, ver {@link #puntoDeSalidaDelCetro}), no de la cara del jugador;
 *    el apuntado sigue saliendo de la mirada, asi que da donde se apunta.
 */
public class BolaDeHielo extends Fireball {
    // Rastro del proyectil: el mismo turquesa claro que el rayo de hielo de la espada
    private static final DustParticleOptions PARTICULA_RASTRO = new DustParticleOptions(
        EspadaDeHielo.COLOR_RAYO_HIELO, 1.0F);

    // Si la bola estaba dentro del agua en el tick anterior. Sirve para reventar justo
    // al entrar en ella (transicion aire -> agua) y para que nacer ya sumergida (disparo
    // estando buceando) no haga estallar la bola en la cara del jugador
    private boolean aguaAnterior;

    /**
     * Constructor vacio que usa el juego al crear la entidad en el cliente (el servidor
     * manda el tipo y los datos sincronizados; el estado real se recibe despues)
     */
    public BolaDeHielo(final EntityType<? extends BolaDeHielo> type, final Level level) {
        super(type, level);
    }

    /**
     * Constructor de disparo: nace en la punta del cetro (ver
     * {@link #puntoDeSalidaDelCetro}, igual que el rayo de hielo sale de la espada) en la
     * direccion apuntada, con el jugador como dueno (asi la explosion usa su game rule de
     * griefing y no se autodaña por ser su propio proyectil)
     */
    public BolaDeHielo(final Level level, final LivingEntity tirador, final Vec3 direccion) {
        this(level, tirador, direccion, puntoDeSalidaDelCetro(level, tirador, direccion));
    }

    /**
     * Variante interna del constructor de disparo: recibe ya resuelto el punto de salida.
     * Es un constructor aparte porque hay que calcularlo ANTES de llamar a super(), que es
     * quien coloca la entidad en el mundo
     */
    private BolaDeHielo(final Level level, final LivingEntity tirador, final Vec3 direccion,
                        final Vec3 salida) {
        super(EspadaDeHielo.ENTIDAD_BOLA_DE_HIELO.get(),
            salida.x, salida.y, salida.z, direccion, level);
        this.setOwner(tirador);
        // Fireball trae por defecto el item "carga de fuego"; este usa el suyo propio
        this.setItem(new ItemStack(EspadaDeHielo.BOLA_DE_HIELO.get()));

        // Si nace dentro del agua, se memoriza para no reventar en el primer tick
        this.aguaAnterior = !level.isClientSide()
            && level.getBlockState(BlockPos.containing(this.position())).is(Blocks.WATER);
    }

    /**
     * Punto del que sale la bola: la punta del cetro que el jugador lleva en la mano, en
     * vez de su cara. Se parte de los ojos (que es donde apunta el jugador) y se aplica el
     * mismo desplazamiento que usa el rayo de la espada ({@link EspadaDeHieloItem}): a la
     * derecha, a la altura de la mano y hacia delante siguiendo la mirada, algo mas lejos
     * porque el cetro es mas largo que la espada.
     *
     * Si hay un bloque justo delante, el avance se recorta para que la bola no nazca dentro
     * de el (reventaria pegada a la cara del jugador): en ese caso nace pegada a la pared.
     */
    private static Vec3 puntoDeSalidaDelCetro(final Level level, final LivingEntity tirador,
                                              final Vec3 direccion) {
        final Vec3 ojo = tirador.getEyePosition();

        // "Derecha" del jugador a partir de su giro horizontal (yaw). Se calcula con el yaw
        // y no con el vector de mirada para que no valga cero mirando recto hacia arriba o abajo
        final float yaw = tirador.getYRot() * ((float) Math.PI / 180.0F);
        final Vec3 derecha = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));

        // Si hay un bloque justo delante, la bola nace pegada a el en vez de dentro
        double adelante = EspadaDeHielo.SALIDA_CETRO_ADELANTE;
        final BlockHitResult choque = level.clip(new ClipContext(
            ojo, ojo.add(direccion.scale(EspadaDeHielo.SALIDA_CETRO_ADELANTE + 0.5)),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, tirador));
        if (choque.getType() != HitResult.Type.MISS) {
            adelante = Math.max(0.0, ojo.distanceTo(choque.getLocation()) - 0.5);
        }

        return tirador.getEyePosition()
            .add(derecha.scale(EspadaDeHielo.SALIDA_CETRO_DERECHA))
            .add(0.0, -EspadaDeHielo.SALIDA_CETRO_ABAJO, 0.0)
            .add(direccion.scale(adelante));
    }

    /**
     * El proyectil revienta en cuanto toca el agua: si el bloque en el que esta es agua
     * y en el tick anterior no lo era (acaba de entrar en la superficie), se dispara la
     * explosion en ese mismo punto en vez de seguir volando dentro. Si nace ya dentro
     * del agua (disparo buceando) no revienta en la cara del jugador: solo cuenta la
     * transicion de aire a agua.
     */
    @Override
    public void tick() {
        super.tick();

        // super.tick() puede haber chocado (y explotado ya) en este mismo tick
        if (this.isRemoved() || !(this.level() instanceof ServerLevel servidor)) {
            return;
        }

        final boolean enAgua = servidor.getBlockState(BlockPos.containing(this.position())).is(Blocks.WATER);
        if (enAgua && !this.aguaAnterior) {
            this.explotar(servidor);
            return;
        }
        this.aguaAnterior = enAgua;
    }

    // El choque normal (bloque o entidad) solo lanza la explosion; ademas se llama desde
    // tick() cuando la bola entra en el agua
    @Override
    protected void onHit(final HitResult hitResult) {
        super.onHit(hitResult);

        if (this.level() instanceof ServerLevel servidor) {
            this.explotar(servidor);
        }
    }

    /**
     * Todo lo que ocurre cuando la bola revienta (contra un bloque, una entidad o la
     * superficie del agua): explosion de potencia
     * {@value EspadaDeHielo#POTENCIA_EXPLOSION_BOLA} (la del ghast es 1) que rompe
     * bloques si el game rule mobGriefing lo permite, igual que la bola del ghast.
     * Despues de explotar:
     *  - ralentiza a las criaturas vivas que alcance el radio de la explosion (menos
     *    al dueno del cetro),
     *  - congela en hielo el agua que rodea el impacto, hasta el borde del crater,
     *  - siembra unos cuantos mechones de fuego azul en el crater (solo si la explosion
     *    puede modificar el terreno),
     *  - y suelta una nube de copos de nieve turquesa.
     */
    private void explotar(final ServerLevel servidor) {
        // El mobGriefing decide si la explosion abre crater y si deja fuego
        final boolean rompeBloques = net.minecraftforge.event.ForgeEventFactory
            .getMobGriefingEvent(servidor, this.getOwner());

        // Ojo: el boolean de explode NO es "romper bloques", es "generar fuego". Va en
        // false para que vanilla no siembre fuego normal en el crater (el romper bloques
        // ya lo decide la interaccion MOB con el mobGriefing). El fuego que deja esta
        // bola es el azul del mod, que se coloca a mano justo debajo (sembrarFuegoAzul)
        this.level().explode(this, this.getX(), this.getY(), this.getZ(),
            EspadaDeHielo.POTENCIA_EXPLOSION_BOLA, false, Level.ExplosionInteraction.MOB);

        // Ralentizacion por la propia explosion (radio = potencia x 2, la medida de
        // la explosion): cualquier criatura viva dentro de ese radio sale lenta, salvo
        // el dueno para que no se perjudique a si mismo
        this.aplicarRalentizacionPorExplosion(servidor);

        // El agua alrededor pasa a hielo (mismo efecto que la espada, en area)
        this.congelarAguaCerca(servidor);

        // Mechones de fuego azul en el crater (bloque propio del mod)
        if (rompeBloques) {
            this.sembrarFuegoAzul(servidor);
        }

        // Aurora helada de la explosion: copos y chispas turquesa alrededor del punto
        servidor.sendParticles(net.minecraft.core.particles.ParticleTypes.SNOWFLAKE,
            this.getX(), this.getY() + 0.5, this.getZ(), 20, 1.2, 1.2, 1.2, 0.05);
        servidor.sendParticles(PARTICULA_RASTRO,
            this.getX(), this.getY() + 0.5, this.getZ(), 12, 1.0, 1.0, 1.0, 0.02);

        this.discard();
    }

    /**
     * Impacto directo contra una entidad: dano de bola de fuego (mismo valor que la del
     * ghast), congelado y el mismo efecto de Ralentizacion que aplica la Espada de Hielo
     * (mismo nivel y duracion, ver {@link EspadaDeHielo#TICKS_RALENTIZACION}), para que
     * al enemigo se le note lo helada que esta
     */
    @Override
    protected void onHitEntity(final EntityHitResult hitResult) {
        super.onHitEntity(hitResult);

        if (this.level() instanceof ServerLevel servidor) {
            final var objetivo = hitResult.getEntity();
            final var fuente = this.damageSources().fireball(this, this.getOwner());

            objetivo.hurtServer(servidor, fuente, EspadaDeHielo.DANIO_IMPACTO_BOLA);
            net.minecraft.world.item.enchantment.EnchantmentHelper
                .doPostAttackEffects(servidor, objetivo, fuente);

            if (objetivo instanceof LivingEntity vivo) {
                if (vivo.canFreeze()) {
                    vivo.setTicksFrozen(Math.max(vivo.getTicksFrozen(), EspadaDeHielo.TICKS_CONGELADO_BOLA));
                }

                // Ralentizacion identica a la de la espada al acertar
                vivo.addEffect(new MobEffectInstance(
                    MobEffects.SLOWNESS,
                    EspadaDeHielo.TICKS_RALENTIZACION,
                    EspadaDeHielo.NIVEL_RALENTIZACION
                ));
                this.level().playSound(null, vivo.getX(), vivo.getEyeY(), vivo.getZ(),
                    SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 0.9F, 0.8F);
            }
        }
    }

    /**
     * Congela en area el agua que rodea el punto de impacto: todos los bloques de agua
     * dentro de un radio de {@value EspadaDeHielo#RADIO_CONGELADO_BOLA} bloques pasan a
     * hielo, igual que al golpear agua con la Espada de Hielo. Como la explosion no
     * destruye el agua, esto se hace justo despues de explotar, para pillar lo que quede
     * alrededor del crater. Si congela algo, suena el cristal de la espada.
     */
    private void congelarAguaCerca(final ServerLevel servidor) {
        final int radio = EspadaDeHielo.RADIO_CONGELADO_BOLA;
        final double radioCuadrado = radio * radio;
        final BlockPos centro = BlockPos.containing(this.position());
        final var dueno = this.getOwner();
        final AABB cajaDueno = (dueno == null) ? null : dueno.getBoundingBox();
        int congelados = 0;

        for (final BlockPos pos : BlockPos.betweenClosed(
                centro.offset(-radio, -radio, -radio), centro.offset(radio, radio, radio))) {
            if (cajaDueno != null && cajaDueno.intersects(new AABB(pos))) {
                continue;
            }
            if (pos.distToCenterSqr(this.position()) <= radioCuadrado
                    && servidor.getBlockState(pos).is(Blocks.WATER)
                    && servidor.setBlock(pos, Blocks.ICE.defaultBlockState(), 3)) {
                congelados++;
            }
        }

        if (congelados > 0) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.7F, 1.15F);
        }
    }

    /**
     * Deja mechones de fuego azul cielo ({@link FuegoAzulBlock}) repartidos por el crater
     * de la explosion. A diferencia del fuego de almas, este bloque no necesita una base
     * de arena/tierra de almas: se mantiene sobre cualquier suelo firme del crater, asi
     * que no hay que generar nada debajo. Si coloca alguno, suena el escape de alma.
     */
    private void sembrarFuegoAzul(final ServerLevel servidor) {
        final var azar = servidor.getRandom();
        final int radio = EspadaDeHielo.RADIO_FUEGOS_AZULES;
        final double radioCuadrado = radio * radio;
        final BlockPos centro = BlockPos.containing(this.position());
        final List<BlockPos> candidatos = new ArrayList<>();

        // Huecos de aire con suelo firme debajo: candidatos a quedarse con fuego
        // (sin fluidos: ni agua ni lava cuentan como suelo)
        for (final BlockPos pos : BlockPos.betweenClosed(
                centro.offset(-radio, -radio, -radio), centro.offset(radio, radio, radio))) {
            final var suelo = servidor.getBlockState(pos.below());
            if (pos.distToCenterSqr(this.position()) <= radioCuadrado
                    && servidor.getBlockState(pos).isAir()
                    && !suelo.isAir() && suelo.getFluidState().isEmpty()) {
                candidatos.add(pos.immutable());
            }
        }

        int colocados = 0;
        while (!candidatos.isEmpty() && colocados < EspadaDeHielo.MAX_FUEGOS_AZULES) {
            final BlockPos pos = candidatos.remove(azar.nextInt(candidatos.size()));
            servidor.setBlock(pos, EspadaDeHielo.FUEGO_AZUL.get().defaultBlockState(), 3);
            colocados++;
        }

        if (colocados > 0) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 0.6F, 1.0F);
        }
    }

    /**
     * La explosion en si aplica el mismo efecto de Ralentizacion que la Espada de Hielo
     * a toda criatura viva dentro del radio de la explosion (potencia x 2), excluyendo
     * al dueno del cetro.
     */
    private void aplicarRalentizacionPorExplosion(final ServerLevel servidor) {
        final double radio = EspadaDeHielo.POTENCIA_EXPLOSION_BOLA * 2.0;
        final double radioCuadrado = radio * radio;
        final Vec3 centro = this.position();
        final var dueno = this.getOwner();
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

    // Rastro turquesa en vez de humo
    @Override
    protected DustParticleOptions getTrailParticle() {
        return PARTICULA_RASTRO;
    }

    // Una bola de hielo no deberia encenderse al chocar con fuego o lava
    @Override
    protected boolean shouldBurn() {
        return false;
    }
}
