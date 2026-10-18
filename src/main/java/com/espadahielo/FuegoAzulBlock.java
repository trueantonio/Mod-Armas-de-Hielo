package com.espadahielo;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bloque de fuego azul cielo que deja la explosion del Cetro de Hielo.
 *
 * Se ve y se comporta como el fuego comun (mismo modelo y animacion de llamas, quema a
 * quien lo pisa, ilumina, suena igual y se apaga de un golpe) con dos diferencias:
 *  - su textura es una copia recoloreada del fuego vanilla, en azul cielo, y
 *  - las criaturas que lo tocan se quedan lentas, igual que con la Espada de Hielo.
 *
 * A diferencia del fuego comun (y del de almas), no necesita un bloque debajo que lo
 * sostenga: al heredar directamente de {@link BaseFireBlock} no define
 * {@code updateShape}, asi que nunca se autodestruye por estar mal colocado. Tampoco se
 * extiende ni se consume (no tiene {@code tick}), de modo que en el crater se queda como
 * seña de la explosion hasta que alguien lo rompa.
 */
public class FuegoAzulBlock extends BaseFireBlock {
    public static final MapCodec<FuegoAzulBlock> CODEC = simpleCodec(FuegoAzulBlock::new);

    // Dano por quemadura, el mismo que aplica el fuego comun (el de almas aplica 2)
    private static final float DANIO_QUEMADURA = 1.0F;

    public FuegoAzulBlock(final Properties propiedades) {
        super(propiedades, DANIO_QUEMADURA);
    }

    @Override
    public MapCodec<FuegoAzulBlock> codec() {
        return CODEC;
    }

    // Se deja como en el fuego comun: solo "prende" sobre bloques inflamables
    @Override
    protected boolean canBurn(final BlockState estado) {
        return estado.ignitedByLava();
    }

    @Override
    protected void entityInside(final BlockState estado, final Level nivel, final BlockPos pos,
            final Entity entidad, final InsideBlockEffectApplier efectos, final boolean dentro) {
        super.entityInside(estado, nivel, pos, entidad, efectos, dentro);

        // Las criaturas que pisan el fuego azul salen ralentizadas, como con la espada.
        // El efecto se renueva mientras sigan dentro (entityInside se llama cada tick)
        if (!nivel.isClientSide() && entidad instanceof LivingEntity vivo) {
            vivo.addEffect(new MobEffectInstance(
                MobEffects.SLOWNESS,
                EspadaDeHielo.TICKS_RALENTIZACION,
                EspadaDeHielo.NIVEL_RALENTIZACION));
        }
    }
}
