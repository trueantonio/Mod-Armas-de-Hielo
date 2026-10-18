package com.espadahielo;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bola de hielo lanzada por el Cetro de Hielo.
 *
 * Se comporta como la bola de fuego del ghast (subclase de Fireball: misma fisica de
 * proyectil "hurting", explosion al tocar bloque o entidad) pero con tres cambios:
 *  - La explosion es mas potente ({@value EspadaDeHielo#POTENCIA_EXPLOSION_BOLA} en vez
 *    de 1) y sigue respetando la regla de game rule de romper bloques (mobGriefing).
 *  - El impacto directo congela al enemigo ademas de hacerle dano.
 *  - El rastro son copos/chispas turquesa en vez de humo, no se enciende en el aire
 *    y su aspecto es el del item "bola_de_hielo" (textura turquesa) gracias a que
 *    Fireball es ItemSupplier y el renderizador del mod usa ThrownItemRenderer.
 */
public class BolaDeHielo extends Fireball {
    // Rastro del proyectil: el mismo turquesa claro que el rayo de hielo de la espada
    private static final DustParticleOptions PARTICULA_RASTRO = new DustParticleOptions(
        EspadaDeHielo.COLOR_RAYO_HIELO, 1.0F);

    /**
     * Constructor vacio que usa el juego al crear la entidad en el cliente (el servidor
     * manda el tipo y los datos sincronizados; el estado real se recibe despues)
     */
    public BolaDeHielo(final EntityType<? extends BolaDeHielo> type, final Level level) {
        super(type, level);
    }

    /**
     * Constructor de disparo: nace a la altura de los ojos del jugador (un pelin abajo,
     * para que no salga de la frente) en la direccion apuntada, con el jugador como
     * dueno (asi la explosion usa su game rule de griefing y no se autodaña por ser su
     * propio proyectil)
     */
    public BolaDeHielo(final Level level, final LivingEntity tirador, final Vec3 direccion) {
        super(EspadaDeHielo.ENTIDAD_BOLA_DE_HIELO.get(),
            tirador.getX(), tirador.getEyeY() - 0.1, tirador.getZ(), direccion, level);
        this.setOwner(tirador);
        // Fireball trae por defecto el item "carga de fuego"; este usa el suyo propio
        this.setItem(new ItemStack(EspadaDeHielo.BOLA_DE_HIELO.get()));
    }

    /**
     * Al tocar un bloque o una entidad: explosion de potencia
     * {@value EspadaDeHielo#POTENCIA_EXPLOSION_BOLA} (la del ghast es 1) que rompe
     * bloques si el game rule mobGriefing lo permite, igual que la bola del ghast.
     * Se aprovecha para soltar una nube de copos de nieve turquesa.
     */
    @Override
    protected void onHit(final HitResult hitResult) {
        super.onHit(hitResult);

        if (this.level() instanceof ServerLevel servidor) {
            final boolean rompeBloques = net.minecraftforge.event.ForgeEventFactory
                .getMobGriefingEvent(servidor, this.getOwner());

            this.level().explode(this, this.getX(), this.getY(), this.getZ(),
                EspadaDeHielo.POTENCIA_EXPLOSION_BOLA, rompeBloques, Level.ExplosionInteraction.MOB);

            // Aurora helada de la explosion: copos y chispas turquesa alrededor del punto
            servidor.sendParticles(net.minecraft.core.particles.ParticleTypes.SNOWFLAKE,
                this.getX(), this.getY() + 0.5, this.getZ(), 20, 1.2, 1.2, 1.2, 0.05);
            servidor.sendParticles(PARTICULA_RASTRO,
                this.getX(), this.getY() + 0.5, this.getZ(), 12, 1.0, 1.0, 1.0, 0.02);

            this.discard();
        }
    }

    /**
     * Impacto directo contra una entidad: dano de bola de fuego (mismo valor que la del
     * ghast) mas congelado, para que la bola se note helada tambien al acertar
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

            if (objetivo instanceof LivingEntity vivo && vivo.canFreeze()) {
                vivo.setTicksFrozen(Math.max(vivo.getTicksFrozen(), EspadaDeHielo.TICKS_CONGELADO_BOLA));
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
