package com.espadahielo;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.util.GeckoLibUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Glaciarock: elemental de hielo que el Cetro de Hielo invoca con un casteo de 3
 * segundos (ver {@link CetroDeHieloItem}).
 *
 * Como es un coloso (escala {@value #ESCALA_GLACIAROCK}x, hitbox de 4 x 4 x 7.25
 * bloques, que cubre el cuerpo entero pero no la envergadura de los brazos) se comporta
 * como un golem de hierro con el doble de fuerza:
 *  - Salud muy alta (200, la del Ender Dragon) y resistencia total al empuje.
 *  - Golpe cuerpo a cuerpo de 30 (el doble que un golem de hierro) con alcance de
 *    {@value #ALCANCE_GOLPE_GLACIAROCK} bloques desde el borde de su cuerpo, una vez por
 *    segundo y con el dano cayendo AL FINAL de la animacion de ataque. El golpe manda al
 *    objetivo unos 10 bloques hacia atras y lo deja ralentizado.
 *  - El fuego y la lava le hacen {@value #MULT_DANO_FUEGO}x de dano (es hielo, pero no
 *    es invulnerable al fuego: le hace mas dano, no menos).
 *  - Detecta mobs hostiles hasta 30 bloques (atributo FOLLOW_RANGE) y solo persigue
 *    hasta {@value #DISTANCIA_PERSECUCION} bloques.
 *  - Sin enemigos cerca vive suelto a tu lado: te empieza a seguir cuando te alejas
 *    {@value #DISTANCIA_SEGUIMIENTO_GLACIAROCK} bloques y se para a
 *    {@value #DISTANCIA_PARADA_GLACIAROCK} (no se te pega), y solo si te vas a mas de
 *    {@value #DISTANCIA_TELEPORTE_GLACIAROCK} se teletransporta a tu lado. Son las
 *    distancias de la calma, no una barrera: en cuanto hay enemigos manda el ataque
 *    (prioridad 2 sobre el 3) y le da igual estar contigo pegado o a media pelea lejos.
 *  - Clic derecho con la mano vacia encima del su dueno: se queda quieto de guardia con
 *    copos de nieve de confirmacion; otro clic derecho vuelve a moverse. No se le puede
 *    desinvocar a mano: solo muere al combate, al morir su dueno o al cambiar este de
 *    dimension.
 *  - Mientras anda congela el agua a {@value #RADIO_CONGELADO_ANDANDO} bloques a su
 *    alrededor y sus pasos suenan pesados (estampidas de T-Rex).
 *  - Salta como un caballo al maximo (fuerza de salto 1.0): el paso de 1.5 le deja subir
 *    andando los escalones normales, y cuando se topa con un repecho de 2-3 bloques que
 *    el navegador no sabe rodear se impulsa el solo (ver
 *    {@link #intentarSaltoDeObstaculo()}).
 *  - Atrae la aggro de los hostiles que tenga a {@value #RADIO_AGGRO_GLACIAROCK} bloques:
 *    le atacan a el igual que atacarian a un jugador, y al que le pega le contesta el
 *    golpe antes que a cualquier otro objetivo. Como un jugador mas: a los que ya van a
 *    por su dueno no se los quita.
 *  - Al morir revienta como la bola de hielo pero con
 *    {@value #POTENCIA_EXPLOSION_MUERTE} de potencia (3 veces la de la bola), ver
 *    {@link ExplosionDeHielo}.
 *
 * Modelado con GeckoLib: la animacion y la textura viven en
 * {@code assets/espadahielo/entity/glaciarock.*}. Solo usa dos animaciones, "walk" y
 * "attack" (esta ultima lanzable a traves de {@link #triggerAnim}).
 */
public class Glaciarock extends TamableAnimal implements GeoEntity {
    // --- Constantes de ajuste: cambiar aqui recalibra al bote ---

    // Escala del modelo y de la hitbox (Attributes.SCALE multiplica ambas). Con 5x la
    // hitbox base de 0.8 x 1.45 queda en 4 x 4 x 7.25 bloques
    public static final float ESCALA_GLACIAROCK = 5.0F;
    // Hitbox base antes de aplicar la escala. El ancho es a proposito mas estrecho que el
    // cuerpo (que mide 5 bloques): asi el coloso cabe por huecos normales y solo choca con
    // el cuerpo, no con los brazos (13 bloques de punta a punta), que atraviesan todo lo
    // que tocan y no enredan su caminar. El alto si cubre el modelo entero, de los pies
    // (que tambien chocan) a la cabeza
    public static final float ANCHO_BASE_GLACIAROCK = 0.8F;
    public static final float ALTO_BASE_GLACIAROCK = 1.45F;

    // Alcance del golpe cuerpo a cuerpo, medido desde el borde de la hitbox (el cuerpo
    // ya son 4 bloques de ancho, asi que esto es lo que se alcanza "por fuera" del coloso)
    public static final double ALCANCE_GOLPE_GLACIAROCK = 3.0;
    // Hasta donde persigue a un objetivo. La deteccion llega mas lejos (FOLLOW_RANGE = 30)
    public static final double DISTANCIA_PERSECUCION = 15.0;
    // Ticks entre que empieza la animacion de ataque y cae el dano (20 = 1 segundo, que
    // es lo que dura la animacion "attack"). Es tambien la cadencia entre golpe y golpe
    public static final int TICKS_ATAQUE_GLACIAROCK = 20;

    // Radio en el que el coloso "tira" de la aggro: cualquier mob hostil que tenga a esta
    // distancia y no vaya a por nadie pasa a atacarle a el, como haria con un jugador. Sin
    // esto solo le atacan si el pega primero, porque no le identifican como jugador
    public static final double RADIO_AGGRO_GLACIAROCK = 16.0;
    // Cada cuantos ticks se vuelve a revisar quien tiene alrededor
    public static final int TICKS_ENTRE_AGGRO = 20;

    // Distancia (de centro a centro) a la que se teletransporta al lado de su dueno si se
    // queda atras. La mascota vanilla lo hace a 12; este es un coloso de 5x y ademas solo
    // llega a teletransportarse si de verdad te has ido lejos (el seguimiento arranca a 20
    // y se para a 11, asi que hasta 40 siempre viene andando)
    public static final double DISTANCIA_TELEPORTE_GLACIAROCK = 40.0;

    // Seguimiento del dueno (solo mientras no haya enemigos, ver registerGoals): arranca a
    // 20 bloques de centro a centro y se para a 11, o sea a 9 del borde de su cuerpo. No son
    // una barrera de separacion: con enemigos cerca se pelea donde haga falta, pegado o no
    public static final double DISTANCIA_SEGUIMIENTO_GLACIAROCK = 20.0;
    public static final double DISTANCIA_PARADA_GLACIAROCK = 11.0;

    // Sin dueno no sigue a nadie ni muere con su invocador. Uno invocado a mano (/summon
    // no le pone dueno) se domestica al jugador mas cercano durante el primer segundo, como
    // si lo hubiese invocado el cetro
    public static final double RADIO_ADOPCION_GLACIAROCK = 16.0;
    public static final int TICKS_ADOPCION_GLACIAROCK = 60;

    // Salto: fuerza de caballo al maximo (1.0; el jugador tiene 0.42) y como mucho un
    // salto cada segundo, y solo contra obstaculos de 2-3 bloques
    public static final int TICKS_ENTRE_SALTOS = 20;
    public static final int ALTURA_MAXIMA_SALTO = 3;

    // Potencia de la explosion de su muerte: la de la bola de hielo (3) por 3. Ojo, que un
    // crater de potencia 9 se lleva por delante unos 12 bloques de terreno
    public static final float POTENCIA_EXPLOSION_MUERTE =
        EspadaDeHielo.POTENCIA_EXPLOSION_BOLA * 3.0F;

    // Multiplicador de dano de fuego/lava que recibe
    public static final float MULT_DANO_FUEGO = 1.5F;

    // Radio (en bloques, horizontal) del agua que congela mientras camina
    public static final int RADIO_CONGELADO_ANDANDO = 5;
    // Cada cuantos ticks se vuelve a mirar el agua alrededor mientras anda
    public static final int TICKS_ENTRE_CONGELADAS = 4;
    // Ticks entre estampida y estampida (el paso pesado, estilo T-Rex)
    public static final int TICKS_ENTRE_PASOS = 10;

    // Controladores de GeckoLib: uno para el estado normal (andar) y otro aparte SOLO
    // para el golpe. Tienen que ir separados: mientras una animacion lanzada con
    // triggerableAnim esta disparada, GeckoLib NO llama al handler de estado del
    // controlador que la lanzo, y como el estado "disparado" solo se limpia con otro
    // setAnimation (que precisamente hace el handler), si el golpe vive en el mismo
    // controlador que el andar, este se queda muerto para siempre tras el primer puñetazo.
    // Es justo para esto para lo que GeckoLib tiene los controladores de solo-disparo.
    private static final String CONTROL_ANDAR = "glaciarock_andar";
    private static final String CONTROL_ATAQUE = "glaciarock_ataque";
    // Tiene que coincidir con lo que hay en glaciarock.animation.json
    private static final String NOMBRE_ANIM_ATAQUE = "attack";
    private static final RawAnimation ANIM_ANDAR = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ANIM_ATAQUE = RawAnimation.begin().thenPlay("attack");

    // Cache de animables de GeckoLib (uno por instancia de entidad)
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // --- Estado del golpe en curso: el dano no sale al pulsar, cae al final de la animacion ---
    // Ticks que faltan para que caiga el golpe pendiente (0 = no hay golpe en curso)
    private int ticksHastaDanio;
    // Id de la entidad que tiene pendiente el golpe
    private int golpePendienteId = -1;
    // Contador para no lanzar una estampida en cada tick
    private int ticksDesdeUltimoPaso;
    // Contador para no ponerse a brincar contra un obstaculo en cada tick
    private int ticksDesdeUltimoSalto;

    /**
     * Constructor vacio que usa el juego al crear la entidad en el cliente (el servidor
     * manda el tipo y los datos sincronizados; el estado real se recibe despues)
     *
     * Ojo con las dimensiones: el tipo esta declarado SIN escala (0.8 x 1.45) y el juego
     * solo vuelve a calcular la caja cuando el atributo SCALE cambia, cosa que en el
     * servidor no ocurre nunca porque alli nadie lee ese atributo (lo leen el renderizador
     * y la camara, que son de cliente). Sin esto, el colisionador se quedaria en 0.8 x
     * 1.45 y el elemental atravesaria arboles y muros mientras su modelo se pinta a 5x.
     * Aqui los atributos ya existen (los crea super), asi que el tamano sale correcto
     * desde el primer tick, en cliente y en servidor
     */
    public Glaciarock(final EntityType<? extends Glaciarock> type, final Level level) {
        super(type, level);
        this.refreshDimensions();
    }

    /**
     * Atributos del Glaciarock. Se registran en {@link EspadaDeHielo#alCrearAtributos}.
     *
     * Los que marcan la diferencia con un mob normal: 200 de salud (la del Ender
     * Dragon), 30 de ataque (el doble que un golem de hierro, cuyo golpe vale 15),
     * resistencia al empuje total, escala 5 y ataque con empuje 2 (el empuje vanilla se
     * calcula como atributo / 2, asi que el golpe manda al objetivo ~10 bloques).
     *
     * La velocidad (0.40) es casi el doble que la de un coloso normal (el golem de hierro
     * tiene 0.25): con 200 de salud y un golpe de 30, si ademas se quedara atras no valdria
     * para nada. Con las prisas de los objetivos (1.5x al atacar) se posiciona casi al
     * galope.
     *
     * El paso vuelve a ser 1.5 (sube andando los escalones de bloque y medio) y la fuerza
     * de salto es 1.0, el maximo de un caballo: cuando se topa con un repecho de 2-3
     * bloques que el navegador no sabe rodear, se impulsa el solo (ver
     * {@link #intentarSaltoDeObstaculo()}). Con la distancia de caida segura alta no se
     * hace dano al caer de sus propios saltos
     */
    public static AttributeSupplier.Builder crearAtributos() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 200.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.40D)
            .add(Attributes.FOLLOW_RANGE, 30.0D)
            .add(Attributes.ATTACK_DAMAGE, 30.0D)
            .add(Attributes.ATTACK_KNOCKBACK, 2.0D)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
            .add(Attributes.STEP_HEIGHT, 1.5D)
            .add(Attributes.JUMP_STRENGTH, 1.0D)
            .add(Attributes.SAFE_FALL_DISTANCE, 10.0D)
            .add(Attributes.SCALE, (double) ESCALA_GLACIAROCK);
    }

    // El Glaciarock no se cria ni come nada
    @Override
    public boolean isFood(final ItemStack stack) {
        return false;
    }

    @Override
    protected void registerGoals() {
        // 0: flotar si acaba en agua (los golems no la hacen, pero este puede caer a un lago)
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 1: guardia quieto (bloquea moverse y saltar mientras este ordenado sentarse)
        this.goalSelector.addGoal(1, new SitWhenOrderedToGoal(this));
        // 2: ataque cuerpo a cuerpo sincronizado con la animacion
        this.goalSelector.addGoal(2, new AtaqueDeGlaciarock(this));
        // 3: seguir al dueno SOLO cuando se aleja {@value #DISTANCIA_SEGUIMIENTO_GLACIAROCK}
        // bloques, corriendo (1.5x) y dejandole en paz hasta los
        // {@value #DISTANCIA_PARADA_GLACIAROCK} (nunca se le pega). Ojo, estas distancias
        // solo mandan cuando no hay enemigos: con enemigos cerca manda el ataque (prioridad
        // 2, por encima de este 3) y le da igual estar pegado o lejos
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.5D,
            (float) DISTANCIA_SEGUIMIENTO_GLACIAROCK, (float) DISTANCIA_PARADA_GLACIAROCK));
        // 4: pasear por el entorno
        this.goalSelector.addGoal(4, new RandomStrollGoal(this, 1.2D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 14.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        // Objetivos, por orden de urgencia. Los dos primeros son de "defensa": lo que le
        // toca a el o a su dueno se atiende antes que salir a cazar por su cuenta
        //
        // 0: si un enemigo le pega, le contesta a ese y a nadie mas
        this.targetSelector.addGoal(0, new HurtByTargetGoal(this));
        // 1: si le pegan a su dueno, se lanza a por el agresor
        this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
        // 2: si su dueno esta peleando con algo, le ayuda con ese algo
        this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
        // 3: si no hay nada mas urgente, caza mobs hostiles (Monster cubre zombies,
        // esqueletos, creepers...). mustSee = true: para detectarlos tiene que verlos; el
        // alcance de busqueda sale del atributo FOLLOW_RANGE (30 bloques)
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Monster.class, true, false) {
            @Override
            public boolean canUse() {
                return !Glaciarock.this.isOrderedToSit() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !Glaciarock.this.isOrderedToSit() && super.canContinueToUse();
            }
        });
    }

    /**
     * Ciclo de vida. La logica de servidor va ANTES de super.tick() a proposito: los
     * objetivos (y por tanto el comienzo de un golpe) se ejecutan dentro de super.tick(),
     * y si el contador bajara despues, un golpe nuevo podria pisar al que esta a punto de
     * caer en este mismo tick.
     */
    @Override
    public void tick() {
        if (this.level() instanceof ServerLevel servidor) {
            this.tickDeServidor(servidor);
        }

        // Si acabamos de reventar porque el dueno ha desaparecido, ya no seguimos: el
        // resto del tick (IA, animacion) se haria sobre una entidad que ya no esta
        if (this.isRemoved()) {
            return;
        }

        super.tick();
    }

    private void tickDeServidor(final ServerLevel servidor) {
        if (!this.isAlive()) {
            return;
        }

        // 0) Si ha aparecido sin dueno (/summon no se lo pone), se domestica al jugador
        //    mas cercano: sin dueno no sigue a nadie ni muere con su invocador
        this.adoptarDueno(servidor);

        // 1) El golpe pendiente cae al final de la animacion de ataque
        if (this.ticksHastaDanio > 0) {
            this.ticksHastaDanio--;
            if (this.ticksHastaDanio == 0) {
                this.aplicarGolpePendiente(servidor);
            }
        }

        // 2) Andando: congelar el agua alrededor y soltar la estampida pesada
        if (this.onGround() && this.estaMoviendose()) {
            if (this.tickCount % TICKS_ENTRE_CONGELADAS == 0) {
                this.congelarAguaAlAndar(servidor);
            }

            if (++this.ticksDesdeUltimoPaso >= TICKS_ENTRE_PASOS) {
                this.ticksDesdeUltimoPaso = 0;
                servidor.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.WARDEN_STEP, SoundSource.NEUTRAL, 2.0F, 0.55F);
            }
        }

        // 3) Salto de obstaculo: si se topa con un repecho de 2-3 bloques, se impulsa
        if (this.ticksDesdeUltimoSalto < TICKS_ENTRE_SALTOS) {
            this.ticksDesdeUltimoSalto++;
        }
        this.intentarSaltoDeObstaculo();

        // 4) Tirar de la aggro de los hostiles de alrededor: le atacan a el igual que
        //    atacarian a un jugador (si no, le ignoran hasta que el pega primero). En
        //    guardia no se hace: seria atraer un ejercito a un coloso que esta quieto
        if (!this.isOrderedToSit() && this.tickCount % TICKS_ENTRE_AGGRO == 0) {
            this.atraerHostiles(servidor);
        }

        // 5) Muere si su dueno muere o cambia de dimension (no se puede desinvocar a mano).
        //    El tick de margen es solo por si al invocarlo el dueno tardase un momento en
        //    resolverse en los datos de la entidad
        if (this.tickCount > 20 && this.isTame()) {
            final LivingEntity dueno = this.getOwner();
            if (dueno == null || !dueno.isAlive() || dueno.level() != this.level()) {
                this.explotarYDesaparecer(servidor);
            }
        }
    }

    /**
     * Un Glaciarock sin dueno no sigue a nadie y ademas no muere con su invocador (las dos
     * cosas cuelgan de {@code isTame()}), asi que uno invocado con /summon se quedaria de
     * decorado. Para que se comporte igual que el que invoca el cetro, durante los primeros
     * segundos de vida se domestica al jugador mas cercano que tenga a mano. El que invoca
     * el cetro ya nace con dueno y no pasa por aqui
     */
    private void adoptarDueno(final ServerLevel servidor) {
        if (this.isTame() || this.tickCount > TICKS_ADOPCION_GLACIAROCK) {
            return;
        }

        final Player cercano = servidor.getNearestPlayer(this, RADIO_ADOPCION_GLACIAROCK);
        if (cercano != null) {
            this.tame(cercano);
            // Un guardian invocado no debe desaparecer por estar lejos de un jugador
            this.setPersistenceRequired();
        }
    }

    /**
     * Salto de obstaculo: se mira el bloque que tiene pegado al borde delantero del cuerpo.
     * Si es un repecho de 2 o 3 bloques de alto (los de 1 ya los sube andando con el paso
     * de 1.5, y contra una muralla mas alta no tiene sentido ponerse a brincar) se impulsa
     * con la fuerza de salto de un caballo al maximo. Va con un margen entre salto y salto
     * para que no parezca que esta rebotando
     */
    private void intentarSaltoDeObstaculo() {
        if (!this.onGround() || this.ticksDesdeUltimoSalto < TICKS_ENTRE_SALTOS) {
            return;
        }

        // Sin choque y sin ganas de andar no hay obstaculo contra el que saltar
        if (!this.horizontalCollision && !this.estaMoviendose()) {
            return;
        }

        // Bloque justo delante del borde de su caja (la caja mide 4x4: el centro queda 2
        // bloques por detras del borde)
        final AABB caja = this.getBoundingBox();
        final Direction delante = this.getDirection();
        final double medioAncho = this.getBbWidth() / 2.0;
        final BlockPos posDelante = BlockPos.containing(
            caja.getCenter().x + delante.getStepX() * (medioAncho + 0.3),
            caja.minY + 0.2,
            caja.getCenter().z + delante.getStepZ() * (medioAncho + 0.3));

        // Escalon de un bloque: lo sube andando, no hace falta saltar
        if (!this.level().getBlockState(posDelante).blocksMotion()
                || !this.level().getBlockState(posDelante.above()).blocksMotion()) {
            return;
        }

        // Altura del repecho: solo interesan los de 2 y 3 bloques
        int alto = 2;
        while (alto <= ALTURA_MAXIMA_SALTO
                && this.level().getBlockState(posDelante.above(alto)).blocksMotion()) {
            alto++;
        }
        if (alto > ALTURA_MAXIMA_SALTO) {
            return;
        }

        this.ticksDesdeUltimoSalto = 0;
        this.jumpFromGround();
    }

    /**
     * Se teletransporta al lado de su dueno si se queda muy atras
     * ({@value #DISTANCIA_TELEPORTE_GLACIAROCK} bloques). La mascota vanilla lo hace a 12;
     * este viene andando desde mucho antes (el seguimiento arranca a 20 y se para a 11), asi
     * que solo recurre al teletransporte cuando de verdad te has ido lejos
     */
    @Override
    public boolean shouldTryTeleportToOwner() {
        final LivingEntity dueno = this.getOwner();
        return dueno != null && this.distanceToSqr(dueno)
            >= DISTANCIA_TELEPORTE_GLACIAROCK * DISTANCIA_TELEPORTE_GLACIAROCK;
    }

    /**
     * Pone al Glaciarock como objetivo de los mobs hostiles que tenga cerca y no tengan a
     * nadie a quien pegar: es lo mas parecido a "que le vean como un jugador". A los que ya
     * van a por su dueno no se les toca: el coloso es un companero mas, no un tanque que
     * se lleve por delante todos los golpes
     */
    private void atraerHostiles(final ServerLevel servidor) {
        for (final Monster monstruo : servidor.getEntitiesOfClass(Monster.class,
                this.getBoundingBox().inflate(RADIO_AGGRO_GLACIAROCK))) {
            // Solo los que estan sin objetivo: si ya van a por un jugador (el dueno o
            // cualquier otro) se les deja, igual que si fuesen a por otro companero
            if (monstruo.isAlive() && monstruo.getTarget() == null) {
                monstruo.setTarget(this);
            }
        }
    }

    /**
     * Comprueba si el Glaciarock se esta moviendo de verdad (los desplazamientos
     * se resuelvan por el navegador). Vale para logica de servidor: pasos, congelar
     * el agua... alli el delta de movimiento es real
     */
    public boolean estaMoviendose() {
        return this.getDeltaMovement().horizontalDistanceSqr() > 0.001D;
    }

    /**
     * Senal de movimiento para la animacion de "walk". En cliente las entidades ajenas
     * no simulan nada: solo interpolan las posiciones que manda el servidor, asi que su
     * deltaMovement se queda en cero y con {@link #estaMoviendose()} la animacion nunca
     * arrancaba. Vanilla calcula walkAnimation en cliente a partir del desplazamiento
     * real entre ticks (calculateEntityAnimation); el desplazamiento crudo del tick
     * ({@code x - xo}) es la misma senal pero sin depender de que walkAnimation se este
     * actualizando, y el delta se queda como refuerzo por si el controlador se procesa
     * en servidor (alli el delta si es real). Con cualquiera de las tres basta
     */
    private boolean seEstaMoviendoseParaAnimacion() {
        if (this.walkAnimation.isMoving() || this.estaMoviendose()) {
            return true;
        }

        final double dx = this.getX() - this.xo;
        final double dz = this.getZ() - this.zo;

        return dx * dx + dz * dz > 1.0E-6D;
    }

    // --- Ataque cuerpo a cuerpo ---

    /**
     * Lanza la animacion de ataque y apunta el golpe: no hace dano todavia. El dano se
     * aplica {@value #TICKS_ATAQUE_GLACIAROCK} ticks despues, cuando la animacion termina
     * (ver {@link #aplicarGolpePendiente})
     */
    private void iniciarGolpe(final LivingEntity objetivo) {
        this.golpePendienteId = objetivo.getId();
        this.ticksHastaDanio = TICKS_ATAQUE_GLACIAROCK;
        // Se llama del servidor: GeckoLib manda el aviso a los clientes que la vean
        this.triggerAnim(CONTROL_ATAQUE, NOMBRE_ANIM_ATAQUE);
    }

    // Si hay un golpe por la mitad (para no interrumpirlo ni encadenar otro)
    private boolean enMedioDeGolpe() {
        return this.ticksHastaDanio > 0;
    }

    // Si el objetivo esta al alcance del puñazo, contando desde el borde del cuerpo
    private boolean alcanzaAGolpe(final LivingEntity objetivo) {
        return this.getBoundingBox().inflate(ALCANCE_GOLPE_GLACIAROCK)
            .intersects(objetivo.getBoundingBox());
    }

    // Si hay objetivo y sigue estando a menos de DISTANCIA_PERSECUCION bloques
    private boolean hayObjetivoCerca() {
        final LivingEntity objetivo = this.getTarget();
        return objetivo != null && objetivo.isAlive()
            && this.distanceToSqr(objetivo) <= DISTANCIA_PERSECUCION * DISTANCIA_PERSECUCION;
    }

    /**
     * El golpe al final de la animacion: dano y empuje vanilla via
     * {@link Mob#doHurtTarget} (usa el atributo de ataque y el de empuje, y suena con
     * {@link #playAttackSound}). Solo sale si el objetivo sigue vivo y al alcance
     */
    private void aplicarGolpePendiente(final ServerLevel servidor) {
        final int idObjetivo = this.golpePendienteId;
        this.golpePendienteId = -1;

        if (idObjetivo < 0 || !this.isAlive()) {
            return;
        }

        final Entity entidad = servidor.getEntity(idObjetivo);
        if (!(entidad instanceof LivingEntity objetivo) || !objetivo.isAlive()
                || !this.alcanzaAGolpe(objetivo)) {
            return;
        }

        if (this.doHurtTarget(servidor, objetivo)) {
            // El golpe deja al enemigo lento, con el mismo efecto que la Espada de Hielo
            objetivo.addEffect(new MobEffectInstance(
                MobEffects.SLOWNESS,
                EspadaDeHielo.TICKS_RALENTIZACION,
                EspadaDeHielo.NIVEL_RALENTIZACION));
        }
    }

    // El crujido de hielo del golpe: suena cuando el dano sale de verdad (al final de la
    // animacion), porque doHurtTarget solo lo llama si el golpe ha cuajado
    @Override
    protected void playAttackSound() {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
            SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 1.5F, 0.7F);
    }

    /**
     * El fuego y la lava le hacen mas dano que a cualquiera ({@value #MULT_DANO_FUEGO}x),
     * no menos: no es inmune, solo le cuesta mas. Cualquier otra fuente de dano pasa tal
     * cual
     */
    @Override
    public boolean hurtServer(final ServerLevel servidor, final DamageSource fuente, final float cantidad) {
        // IS_FIRE cubre fuego, brasas, bolas de fuego... y ademas se comprueba la lava
        // por su cuenta por si no entra en esa etiqueta
        final float dano = (fuente.is(DamageTypeTags.IS_FIRE) || fuente.is(DamageTypes.LAVA))
            ? cantidad * MULT_DANO_FUEGO
            : cantidad;

        return super.hurtServer(servidor, fuente, dano);
    }

    // --- Interaccion ---

    /**
     * Clic derecho con la mano vacia y siendo el dueno: alterna la guardia (quieto mirando
     * a los lados) con unos copos de nieve de confirmacion. Al entrar en guardia se queda
     * quieto; al salir, vuelve a moverse por su cuenta. No hay forma de desinvocarlo
     */
    @Override
    public InteractionResult mobInteract(final Player jugador, final InteractionHand mano) {
        if (mano != InteractionHand.MAIN_HAND
                || !jugador.getItemInHand(mano).isEmpty()
                || !this.isOwnedBy(jugador)) {
            return super.mobInteract(jugador, mano);
        }

        // El cliente solo necesita saber que la interaccion va a cuajar (brazo animado);
        // el cambio de estado lo hace el servidor
        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        this.setOrderedToSit(!this.isOrderedToSit());

        // Confirmacion visual de la orden
        ((ServerLevel) this.level()).sendParticles(ParticleTypes.SNOWFLAKE,
            this.getX(), this.getY() + 1.5, this.getZ(), 16, 2.5, 1.0, 2.5, 0.02);

        return InteractionResult.SUCCESS;
    }

    // --- Congelar el agua mientras camina ---

    /**
     * Convierte en hielo el agua (solo la de fuente, igual que la espada) a
     * {@value #RADIO_CONGELADO_ANDANDO} bloques a la redonda en horizontal. La distancia
     * se mide en el plano porque el centro del coloso esta 3.6 bloques sobre sus pies: con
     * una esfera de radio 5 casi no le llegaria el agua a los talones
     */
    private void congelarAguaAlAndar(final ServerLevel servidor) {
        final int radio = RADIO_CONGELADO_ANDANDO;
        final double radioCuadrado = radio * radio;
        final Vec3 centro = this.position();
        final BlockPos esquina = BlockPos.containing(centro);

        for (final BlockPos pos : BlockPos.betweenClosed(
                esquina.offset(-radio, -2, -radio), esquina.offset(radio, 2, radio))) {
            final double dx = pos.getX() + 0.5 - centro.x;
            final double dz = pos.getZ() + 0.5 - centro.z;
            if (dx * dx + dz * dz > radioCuadrado) {
                continue;
            }

            if (servidor.getFluidState(pos).is(Fluids.WATER)) {
                servidor.setBlock(pos, Blocks.ICE.defaultBlockState(), 3);
            }
        }
    }

    // --- Muerte ---

    /**
     * La muerte del Glaciarock es la explosion de la bola de hielo multiplicada por 3:
     * potencia {@value #POTENCIA_EXPLOSION_MUERTE}, rompe bloques si el game rule lo
     * permite, congela el agua, siembra el crater de fuego azul y ralentiza a todo lo que
     * alcanza. Todo lo que decora el crater escala con la potencia (ver
     * {@link ExplosionDeHielo})
     */
    @Override
    public void die(final DamageSource fuente) {
        super.die(fuente);

        if (this.level() instanceof ServerLevel servidor) {
            ExplosionDeHielo.explotar(servidor, this, this.position(), this.getOwner(),
                POTENCIA_EXPLOSION_MUERTE);
        }
    }

    /**
     * Se acaba el elemental sin pasar por la animacion de muerte: mismos efectos que
     * {@link #die}. Sirve para cuando su dueno desaparece o cambia de dimension
     */
    private void explotarYDesaparecer(final ServerLevel servidor) {
        ExplosionDeHielo.explotar(servidor, this, this.position(), this.getOwner(),
            POTENCIA_EXPLOSION_MUERTE);
        this.discard();
    }

    // --- Colisiones: bloquea el camino y no se mueve ---

    // Con esto el coloso es un obstaculo solido: el jugador no puede atravesarlo (es lo
    // mismo que hace el Shulker para ser un bloque que no se atraviesa)
    @Override
    public boolean canBeCollidedWith(final Entity otro) {
        return true;
    }

    // El tampoco atraviesa a los jugadores (ni a nadie con lo que choque): cualquier otra
    // entidad la aparta con el empuje normal en vez de dejarla pasar
    @Override
    public boolean canCollideWith(final Entity entidad) {
        return entidad instanceof Player || entidad.canBeCollidedWith(this);
    }

    // No es empujable: ni el jugador ni los mobs lo mueven de donde esta
    @Override
    public boolean isPushable() {
        return false;
    }

    // --- GeckoLib ---

    @Override
    public void registerControllers(final AnimatableManager.ControllerRegistrar registrar) {
        // Controlador del estado normal: anda mientras se mueve y no esta sentado
        registrar.add(new AnimationController<>(CONTROL_ANDAR, 5, estado -> {
            if (this.seEstaMoviendoseParaAnimacion() && !this.isInSittingPose()) {
                return estado.setAndContinue(ANIM_ANDAR);
            }
            return PlayState.STOP;
        }));

        // Controlador de solo-disparo para el golpe: su handler siempre para el estado,
        // asi que no interfiere con el de andar (ver comentario de CONTROL_ATAQUE)
        registrar.add(new AnimationController<>(CONTROL_ATAQUE, 5, estado -> PlayState.STOP)
            .triggerableAnim(NOMBRE_ANIM_ATAQUE, ANIM_ATAQUE));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    // Los tamables no se reproducen: Animal lo exige abstracto
    @Override
    public AgeableMob getBreedOffspring(final ServerLevel level, final AgeableMob other) {
        return null;
    }

    /**
     * Ataque cuerpo a cuerpo propio: la unica diferencia con el de vanilla es que el dano
     * no sale al entrar en alcance, sino cuando {@link Glaciarock} da cuenta de que la
     * animacion de ataque ha terminado (20 ticks despues). Mientras dura el gesto no se
     * interrumpe ni se encadena otro, y solo se golpea dentro del alcance de
     * {@value #ALCANCE_GOLPE_GLACIAROCK} bloques y de {@value #DISTANCIA_PERSECUCION}
     * bloques de distancia
     */
    private static final class AtaqueDeGlaciarock extends MeleeAttackGoal {
        private final Glaciarock glaciarock;

        private AtaqueDeGlaciarock(final Glaciarock glaciarock) {
            // 1.5 de velocidad de aproximacion: es un coloso, pero tiene que llegar a
            // tiempo al cuerpo a cuerpo (1.15 lo dejaba corto al posicionarse)
            super(glaciarock, 1.5D, true);
            this.glaciarock = glaciarock;
        }

        @Override
        public boolean canUse() {
            // Un golpe en vuelo no se corta ni aunque desaparezca el objetivo
            if (this.glaciarock.enMedioDeGolpe()) {
                return true;
            }
            if (this.glaciarock.isOrderedToSit() || !this.glaciarock.hayObjetivoCerca()) {
                return false;
            }
            return super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            if (this.glaciarock.enMedioDeGolpe()) {
                return true;
            }
            if (this.glaciarock.isOrderedToSit() || !this.glaciarock.hayObjetivoCerca()) {
                return false;
            }
            return super.canContinueToUse();
        }

        @Override
        protected void checkAndPerformAttack(final LivingEntity objetivo) {
            // Aqui solo empieza el gesto: mira al objetivo con los ojos y lanza la
            // animacion, y el dano cae despues, en el tick de la entidad
            if (this.glaciarock.enMedioDeGolpe()
                    || !this.mob.getSensing().hasLineOfSight(objetivo)
                    || !this.glaciarock.alcanzaAGolpe(objetivo)) {
                return;
            }

            this.glaciarock.iniciarGolpe(objetivo);
        }
    }
}
