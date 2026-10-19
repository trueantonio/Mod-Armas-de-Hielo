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
     *
     * Es publico porque el casteo del Glaciarock tambien lo usa para soltar las particulas
     * de hielo justo en la punta del cetro (ver CetroDeHieloItem)
     */
    public static Vec3 puntoDeSalidaDelCetro(final Level level, final LivingEntity tirador,
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
     * superficie del agua): esta delegado en {@link ExplosionDeHielo}, que es el mismo
     * efecto helado que produce la muerte del Glaciarock (explosion de potencia
     * {@value EspadaDeHielo#POTENCIA_EXPLOSION_BOLA}, ralentizacion, agua congelada,
     * fuego azul en el crater y copos). Despues la bola se borra a si misma
     */
    private void explotar(final ServerLevel servidor) {
        ExplosionDeHielo.explotar(servidor, this, this.position(), this.getOwner());
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
