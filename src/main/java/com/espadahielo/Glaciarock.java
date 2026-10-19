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
import net.minecraft.world.phys.Vec2;
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
 *  - Cuando esta en el suelo congela la superficie del agua en un cuadrado de
 *    {@value #LADO_CONGELADO_GLACIAROCK} x {@value #LADO_CONGELADO_GLACIAROCK} bloques a su
 *    alrededor, y sus pasos suenan pesados (estampidas de T-Rex).
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

    // --- Posesion: clic derecho + agachado con el cetro para meterse dentro ---
    // Distancia a la que se le busca el Glaciarock al jugador que pide la posesion
    public static final double RADIO_POSESION_GLACIAROCK = 64.0;
    // Ticks que tiene que llevar el coloso sin jinete para que alguien pueda entrar. Hace
    // falta sobre todo por el gesto de salir: agacharse para bajarse es tambien el de
    // entrar, y sin esta espera el jugador que se acaba de bajar volveria a montarse solo
    public static final int TICKS_ESPERA_POSESION_GLACIAROCK = 20;
    // Cuantos ticks se recuerda una peticion de posesion que todavia no se ha podido
    // atender. Montar agachado esta prohibido (el juego baja al jinete al tick siguiente,
    // ver Player#rideTick), asi que hay que esperar a que el jugador suelte el agachado
    public static final int TICKS_CADUCIDAD_POSESION_GLACIAROCK = 100;
    // Margen entre salto y salto del piloto: la mitad que el de la IA (para que no parezca
    // que va botando solo), pero lo bastante corto como para saltar al ritmo que se quiera
    public static final int TICKS_ENTRE_SALTOS_JINETE = 10;
    // Donde se sienta el piloto, en unidades del modelo base (SIN la escala): a la altura
    // de los ojos de la cabeza y algo por delante del centro del cuerpo. El atributo SCALE
    // multiplica tambien este punto por 5, como al resto de la hitbox (la camara queda a
    // 0.88 x 5 = 4.4 sobre los pies del coloso, mas los 1.62 de los ojos del jugador, o sea
    // a 6 sobre el suelo: la altura de la cabeza del modelo)
    public static final Vec3 SITIO_JINETE_GLACIAROCK = new Vec3(0.0, 0.88, 0.20);
    // Impulso hacia arriba con el que sale despedido el piloto al morir el coloso o al
    // quedarle muy poca vida ("como si saliese del robot"). Con 3.0 sube unos 37 bloques
    // con la gravedad normal del jugador
    public static final double IMPULSO_EYECCION_GLACIAROCK = 3.0;
    // Ticks de caida lenta que se le dan al salir despedido: el coloso revienta dejando un
    // crater y el piloto sale volando por encima de el, pero sin estamparse contra el suelo.
    // Con el impulso de 3.0 el vuelo entero dura unos 132 ticks, asi que se deja margen de
    // sobra para que el efecto siga activo al tomar tierra (la caida lenta anula el dano)
    public static final int TICKS_CAIDA_LENTA_EYECCION_GLACIAROCK = 200;
    // Vida a la que el coloso expulsa al piloto por su cuenta, antes de que lo maten: asi
    // no le pilla la explosion de dentro. Estando tan tocado tampoco deja que nadie vuelva
    // a meterse (saldria despedido al momento)
    public static final float VIDA_EXPULSION_JINETE_GLACIAROCK = 25.0F;
    // Cono de la mirada (en grados, mitad a cada lado) dentro del que el piloto busca a
    // quien pegar al hacer clic izquierdo desde dentro
    public static final double ANGULO_GOLPE_JINETE = 65.0;

    // Potencia de la explosion de su muerte: la de la bola de hielo (3) por 3. Ojo, que un
    // crater de potencia 9 se lleva por delante unos 12 bloques de terreno
    public static final float POTENCIA_EXPLOSION_MUERTE =
        EspadaDeHielo.POTENCIA_EXPLOSION_BOLA * 3.0F;

    // Multiplicador de dano de fuego/lava que recibe
    public static final float MULT_DANO_FUEGO = 1.5F;

    // Lado (en bloques) del cuadrado de agua que congela a su alrededor cuando esta en el
    // suelo: 11 = un 11x11 centrado en el, o sea 5 bloques hacia cada lado
    public static final int LADO_CONGELADO_GLACIAROCK = 11;
    // Cada cuantos ticks se vuelve a mirar el agua alrededor
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

    // --- Estado de la posesion (el jugador pilotando desde dentro) ---
    // Jugador que ha pedido entrar y todavia no ha podido (ver #actualizarPosesionPendiente)
    private Player jinetePendiente;
    // Ticks que lleva el coloso sin jinete
    private int ticksSinJinete = TICKS_ESPERA_POSESION_GLACIAROCK;
    // Ticks que quedan para olvidar la peticion de posesion pendiente
    private int ticksCaducidadPosesion;
    // Cooldown del salto del piloto (el de #intentarSaltoDeObstaculo es de otro caso)
    private int ticksDesdeUltimoSaltoJinete;

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

        // 0.b) Si alguien ha pedido posesion con el cetro, se le mete dentro en cuanto se
        //      pueda (ver #actualizarPosesionPendiente)
        this.actualizarPosesionPendiente();

        // 0.c) Si al coloso le queda poca vida y va pilotado, se saca al piloto antes de que
        //      lo maten: asi no le pilla la explosion de dentro (ver #expulsarJineteSiEstaDebil)
        this.expulsarJineteSiEstaDebil(servidor);

        // 1) El golpe pendiente cae al final de la animacion de ataque
        if (this.ticksHastaDanio > 0) {
            this.ticksHastaDanio--;
            if (this.ticksHastaDanio == 0) {
                this.aplicarGolpePendiente(servidor);
            }
        }

        // 2) En el suelo: congelar la superficie del agua de alrededor y, andando, soltar la
        //    estampida pesada
        if (this.onGround()) {
            if (this.tickCount % TICKS_ENTRE_CONGELADAS == 0) {
                this.congelarAguaAlrededor(servidor);
            }

            if (this.estaMoviendoseDeVerdad()
                    && ++this.ticksDesdeUltimoPaso >= TICKS_ENTRE_PASOS) {
                this.ticksDesdeUltimoPaso = 0;
                servidor.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.WARDEN_STEP, SoundSource.NEUTRAL, 2.0F, 0.55F);
            }
        }

        // 3) Salto de obstaculo: si se topa con un repecho de 2-3 bloques, se impulsa. Con
        //    jinete no se hace: el que salta entonces es el piloto con la barra espaciadora
        //    (ver #tickRidden)
        if (this.ticksDesdeUltimoSalto < TICKS_ENTRE_SALTOS) {
            this.ticksDesdeUltimoSalto++;
        }
        if (!this.tieneJinete()) {
            this.intentarSaltoDeObstaculo();
        }

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
     * Movimiento real del coloso para la logica de servidor (congelar el agua, los pasos).
     * Ademas del delta de movimiento se mira el desplazamiento crudo del tick
     * ({@code x - xo}) porque, cuando lo pilota un jugador, el movimiento lo simula el
     * cliente del piloto y en el servidor el delta se queda a cero (ver
     * {@code LivingEntity#travelRidden}, que lo pone a ZERO en el lado que no simula):
     * con {@link #estaMoviendose()} solo, los efectos no se dispararian nunca
     */
    private boolean estaMoviendoseDeVerdad() {
        if (this.estaMoviendose()) {
            return true;
        }

        final double dx = this.getX() - this.xo;
        final double dz = this.getZ() - this.zo;

        return dx * dx + dz * dz > 1.0E-6D;
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
        return this.walkAnimation.isMoving() || this.estaMoviendoseDeVerdad();
    }

    // --- Posesion: el jugador se mete dentro del coloso y lo pilota ---

    /**
     * El Glaciarock vivo y domesticado que tenga ese jugador mas cerca dentro del radio
     * (null si no tiene ninguno a mano). Es lo que busca el cetro al pedir la posesion
     */
    public static Glaciarock buscarDeJugador(final Player jugador, final double radio) {
        Glaciarock mejor = null;
        double mejorDistancia = radio * radio;

        for (final Glaciarock candidato : jugador.level().getEntitiesOfClass(Glaciarock.class,
                jugador.getBoundingBox().inflate(radio),
                elemental -> elemental.isAlive() && elemental.isOwnedBy(jugador))) {
            final double distancia = jugador.distanceToSqr(candidato);
            if (distancia <= mejorDistancia) {
                mejorDistancia = distancia;
                mejor = candidato;
            }
        }

        return mejor;
    }

    // Lo esta pilotando un jugador
    public boolean tieneJinete() {
        return this.getFirstPassenger() instanceof Player;
    }

    /**
     * Peticion de posesion (clic derecho y agachado con el cetro en la mano). No se monta
     * al jugador aqui mismo: montar agachado esta prohibido (el juego mira
     * {@code isShiftKeyDown} y le bajaria al tick siguiente, ver Player#rideTick), asi que
     * se apunta la peticion y es el tick del coloso el que monta cuando el jugador suelte
     * el agachado (ver {@link #actualizarPosesionPendiente}). La peticion caduca a los
     * {@value #TICKS_CADUCIDAD_POSESION_GLACIAROCK} ticks
     */
    public void pedirPosesion(final Player jugador) {
        this.jinetePendiente = jugador;
        this.ticksCaducidadPosesion = TICKS_CADUCIDAD_POSESION_GLACIAROCK;
    }

    /**
     * Repasa la peticion de posesion pendiente y, si ya se puede, mete al jugador dentro.
     * Se esperan dos cosas: que el jugador suelte el agachado (montar agachado duraria un
     * tick) y que hayan pasado {@value #TICKS_ESPERA_POSESION_GLACIAROCK} ticks sin jinete
     * (agacharse para salir es el mismo gesto que para entrar: sin esa espera, el jugador
     * que se acaba de bajar volveria a montarse solo)
     */
    private void actualizarPosesionPendiente() {
        if (this.tieneJinete()) {
            this.ticksSinJinete = 0;
        } else if (this.ticksSinJinete < TICKS_ESPERA_POSESION_GLACIAROCK) {
            this.ticksSinJinete++;
        }

        if (this.jinetePendiente == null) {
            return;
        }

        final Player jugador = this.jinetePendiente;

        // Peticiones que ya no valen: caducada, jugador muerto, en otra dimension,
        // ya montado en algo o coloso que ya no es suyo
        if (--this.ticksCaducidadPosesion <= 0
                || !this.isAlive() || !this.isOwnedBy(jugador)
                || !jugador.isAlive() || jugador.level() != this.level()
                || jugador.isPassenger()) {
            this.jinetePendiente = null;
            return;
        }

        // Todavia no toca: sigue agachado o el coloso acaba de quedarse sin jinete
        if (jugador.isShiftKeyDown()
                || this.ticksSinJinete < TICKS_ESPERA_POSESION_GLACIAROCK) {
            return;
        }

        this.jinetePendiente = null;
        this.montarJinete(jugador);
    }

    /**
     * Mete al jugador dentro del coloso. El jugador desaparece del mundo (invisible) y los
     * golpes se los come el Glaciarock entero: el dano al piloto lo cancela
     * {@code EspadaDeHielo#esProtegidoPorElGlaciarock} mientras vaya montado. El movimiento
     * lo lleva el propio jugador desde su cliente (el "conductor" del vehiculo es el, ver
     * {@link #getControllingPassenger}) y para salir basta con agacharse, como de cualquier
     * montura. Todo esto es cosa del servidor
     */
    private void montarJinete(final Player jugador) {
        // force = true: montar esta prohibido mientras el jugador va agachado
        // (Entity#canRide) y aqui ya se ha esperado a que lo suelte
        if (!jugador.startRiding(this, true, true)) {
            return;
        }

        jugador.setInvisible(true);
        this.ticksSinJinete = 0;

        final ServerLevel servidor = (ServerLevel) this.level();
        servidor.sendParticles(ParticleTypes.SNOWFLAKE, jugador.getX(), jugador.getY() + 1.0,
            jugador.getZ(), 60, 0.6, 0.8, 0.6, 0.05);
        servidor.playSound(null, this.getX(), this.getY(), this.getZ(),
            SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 1.5F, 0.6F);

        // Como salir se lo dice el juego solo al montar ("pulsa <tecla> para desmontar",
        // ver ClientPacketListener#handleSetEntityPassengersPacket)
    }

    /**
     * El jugador se ha bajado del coloso (agachandose, porque ha muerto o porque ha
     * desaparecido): se le devuelve la visibilidad que le quito la posesion. Se hace desde
     * {@link #removePassenger} para que valga cualquier via de salida, y solo en el
     * servidor, que es quien manda sobre ese estado
     */
    private void alBajarElJinete(final Player jugador) {
        jugador.setInvisible(false);
        this.ticksSinJinete = 0;
        this.jinetePendiente = null;
    }

    @Override
    protected void removePassenger(final Entity pasajero) {
        super.removePassenger(pasajero);

        if (!this.level().isClientSide() && pasajero instanceof Player jugador) {
            this.alBajarElJinete(jugador);
        }
    }

    /**
     * Con la vida por los suelos ({@value #VIDA_EXPULSION_JINETE_GLACIAROCK} puntos o
     * menos) el coloso no se deja pilotar: cualquiera que se meta dentro saldria despedido
     * al tick siguiente, asi que mejor no dejarle entrar y avisar. El cetro lo consulta
     * antes de pedir la posesion
     */
    public boolean estaDemasiadoHeridoParaPilotar() {
        return this.getHealth() <= VIDA_EXPULSION_JINETE_GLACIAROCK;
    }

    /**
     * Si le queda poca vida y lleva piloto, le saca de dentro antes de que lo maten: asi no
     * le pilla la explosion de dentro cuando reviente. Mismo empujon que el de la muerte
     * (ver {@link #expulsarAlJinete}), que es el gesto de "salir del robot"
     */
    private void expulsarJineteSiEstaDebil(final ServerLevel servidor) {
        if (this.tieneJinete() && this.estaDemasiadoHeridoParaPilotar()) {
            this.expulsarAlJinete(servidor);
        }
    }

    /**
     * Expulsion del piloto: sale disparado hacia arriba, "como si saliese del robot", con
     * unos ticks de caida lenta para que no se mate al caer. Se usa al morir el coloso (y
     * entonces debajo le queda el crater de su propia explosion, por eso se le saca ANTES
     * de reventar) y tambien antes, cuando le queda poca vida (ver
     * {@link #expulsarJineteSiEstaDebil})
     */
    private void expulsarAlJinete(final ServerLevel servidor) {
        if (!(this.getFirstPassenger() instanceof Player jugador)) {
            return;
        }

        // stopRiding le devuelve la visibilidad (removePassenger)
        jugador.stopRiding();
        jugador.setDeltaMovement(0.0, IMPULSO_EYECCION_GLACIAROCK, 0.0);
        // Sin esto el cliente no se entera del empujon: el movimiento solo viaja a los
        // clientes de las entidades marcadas
        jugador.hurtMarked = true;
        jugador.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING,
            TICKS_CAIDA_LENTA_EYECCION_GLACIAROCK));

        servidor.sendParticles(ParticleTypes.SNOWFLAKE, jugador.getX(),
            jugador.getY() + 0.5, jugador.getZ(), 40, 0.5, 0.5, 0.5, 0.2);
        servidor.playSound(null, jugador.getX(), jugador.getY(), jugador.getZ(),
            SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 1.5F, 1.4F);
    }

    // --- Control del jinete: el piloto lleva al coloso ---

    /**
     * Quien lleva al coloso es su pasajero si es un jugador. Es la pieza clave de toda la
     * posesion: en 26.2 el movimiento del vehiculo lo simula el cliente de quien lo lleva
     * (el servidor solo le copia la posicion, ver {@code Entity#isClientAuthoritative}), y
     * ese "quien lo lleva" sale de aqui. Ademas, con un jugador al mando el servidor deja
     * de considerar al coloso una IA: ni objetivos ni paseos (ver {@code isEffectiveAi})
     *
     * Mob solo acepta mobs, asi que hay que reescribirlo para que valga un jugador
     */
    @Override
    public LivingEntity getControllingPassenger() {
        if (this.getFirstPassenger() instanceof Player jugador) {
            return jugador;
        }

        return super.getControllingPassenger();
    }

    /**
     * Los controles del piloto, calcados de los de un caballo: adelante y atras con el
     * movimiento, lateral a media velocidad y marcha atras a un cuarto (un coloso de 5x va
     * sobrado por delante, pero dando marcha atras es torpe)
     */
    @Override
    protected Vec3 getRiddenInput(final Player jinete, final Vec3 entradaPropia) {
        final float lateral = jinete.xxa * 0.5F;
        float adelante = jinete.zza;
        if (adelante <= 0.0F) {
            adelante *= 0.25F;
        }

        return new Vec3(lateral, 0.0, adelante);
    }

    @Override
    protected float getRiddenSpeed(final Player jinete) {
        return (float) this.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    /**
     * Cada tick de piloto: el coloso gira con la mirada de quien le lleva (la mitad de
     * inclinacion arriba/abajo, como un caballo) y salta con la barra espaciadora, con un
     * margen de {@value #TICKS_ENTRE_SALTOS_JINETE} ticks entre salto y salto. El salto
     * solo se pide donde se simula el movimiento (el cliente del piloto), que es el que
     * manda
     */
    @Override
    protected void tickRidden(final Player jinete, final Vec3 entrada) {
        final Vec2 rotacion = new Vec2(jinete.getXRot() * 0.5F, jinete.getYRot());
        this.setRot(rotacion.y, rotacion.x);
        this.yRotO = this.yBodyRot = this.yHeadRot = this.getYRot();

        if (this.ticksDesdeUltimoSaltoJinete < TICKS_ENTRE_SALTOS_JINETE) {
            this.ticksDesdeUltimoSaltoJinete++;
        }

        if (this.isLocalInstanceAuthoritative() && this.onGround() && jinete.isJumping()
                && this.ticksDesdeUltimoSaltoJinete >= TICKS_ENTRE_SALTOS_JINETE) {
            this.ticksDesdeUltimoSaltoJinete = 0;
            this.jumpFromGround();
        }
    }

    // --- Ataque cuerpo a cuerpo ---

    /**
     * Lanza la animacion de ataque y apunta el golpe: no hace dano todavia. El dano se
     * aplica {@value #TICKS_ATAQUE_GLACIAROCK} ticks despues, cuando la animacion termina
     * (ver {@link #aplicarGolpePendiente}). Con objetivo null solo se lanza el gesto (el
     * puñetazo al aire del piloto)
     */
    private void iniciarGolpe(final LivingEntity objetivo) {
        this.golpePendienteId = objetivo == null ? -1 : objetivo.getId();
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
     * El puñetazo que pide el piloto con el clic izquierdo (ver
     * {@link PaqueteAtaqueGlaciarock}). Es el golpe de siempre - animacion y dano al final
     * de los {@value #TICKS_ATAQUE_GLACIAROCK} ticks - pero eligiendo el objetivo por
     * delante de la mirada, que es lo que espera quien apunta. Sin nadie al que darle se
     * lanza igual el gesto: el puñetazo al aire
     */
    public void golpearComoJinete() {
        if (!this.isAlive() || this.enMedioDeGolpe() || !this.tieneJinete()) {
            return;
        }

        this.iniciarGolpe(this.buscarObjetivoDelJinete());
    }

    /**
     * A quien le pega el piloto: el ser vivo mas cercano que tenga dentro del cono de la
     * mirada ({@value #ANGULO_GOLPE_JINETE} grados a cada lado) y del alcance del golpe.
     * Quedan fuera el propio coloso, su piloto, quien viaje con el y las mascotas del
     * piloto (y sus aliados): a esos no les pega su propio elemental
     */
    private LivingEntity buscarObjetivoDelJinete() {
        final Vec3 mirada = this.getViewVector(1.0F);
        final double cosenoMinimo = Math.cos(Math.toRadians(ANGULO_GOLPE_JINETE));
        final Player jinete = this.getFirstPassenger() instanceof Player jugador ? jugador : null;

        LivingEntity mejor = null;
        double mejorDistancia = Double.MAX_VALUE;

        for (final LivingEntity candidato : this.level().getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(ALCANCE_GOLPE_GLACIAROCK))) {
            if (candidato == this || candidato == jinete || !candidato.isAlive()
                    || candidato.isSpectator() || candidato.getVehicle() == this
                    || (jinete != null && this.esAliadoDelJinete(candidato, jinete))) {
                continue;
            }

            final Vec3 haciaEl = candidato.position().subtract(this.position()).normalize();
            if (mirada.dot(haciaEl) < cosenoMinimo) {
                continue;
            }

            final double distancia = this.distanceToSqr(candidato);
            if (distancia < mejorDistancia) {
                mejorDistancia = distancia;
                mejor = candidato;
            }
        }

        return mejor;
    }

    // Las mascotas del piloto (y sus aliados) no son objetivo de su propio coloso
    private boolean esAliadoDelJinete(final LivingEntity candidato, final Player jinete) {
        return candidato.isAlliedTo(jinete)
            || (candidato instanceof TamableAnimal mascota && mascota.isOwnedBy(jinete));
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
     * a los lados) con una nube de copos de nieve y un crujido de confirmacion. Al entrar
     * en guardia se para en seco donde esta; al salir, vuelve a moverse por su cuenta. No
     * hay forma de desinvocarlo
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

        final boolean sentado = !this.isOrderedToSit();
        this.setOrderedToSit(sentado);

        // Parar en seco: la orden de sentarse frena la navegacion, pero aqui se corta ya
        // mismo el movimiento horizontal para que no se deslice ni un bloque
        this.getNavigation().stop();
        this.setDeltaMovement(0.0, this.getDeltaMovement().y, 0.0);

        this.efectoDeGuardia((ServerLevel) this.level(), sentado);

        return InteractionResult.SUCCESS;
    }

    /**
     * Aviso de que la guardia ha cambiado: una nube de copos de nieve por todo el cuerpo
     * (se ve desde lejos, que es de lo que se trata) mas un remolino bajo a ras de suelo y
     * un crujido de nieve. Al sentarse suena mas grave que al volver a moverse, para
     * distinguir las dos ordenes
     */
    private void efectoDeGuardia(final ServerLevel servidor, final boolean sentado) {
        final double alto = this.getBbHeight();
        final double ancho = this.getBbWidth();

        // Nube de copos por todo el coloso
        servidor.sendParticles(ParticleTypes.SNOWFLAKE,
            this.getX(), this.getY() + alto * 0.5, this.getZ(),
            140, ancho * 0.55, alto * 0.45, ancho * 0.55, 0.06);

        // Remolino bajo, a ras de suelo, para que se note tambien de lejos
        servidor.sendParticles(ParticleTypes.SNOWFLAKE,
            this.getX(), this.getY() + 0.25, this.getZ(),
            60, ancho * 0.9, 0.15, ancho * 0.9, 0.03);

        servidor.playSound(null, this.getX(), this.getY(), this.getZ(),
            SoundEvents.SNOW_BREAK, SoundSource.NEUTRAL, 1.6F, sentado ? 0.65F : 1.0F);
    }

    // --- Congelar el agua alrededor ---

    /**
     * Congela la superficie del agua que tenga alrededor: un cuadrado de
     * {@value #LADO_CONGELADO_GLACIAROCK} x {@value #LADO_CONGELADO_GLACIAROCK} bloques
     * centrado en el coloso. En cada columna se hiela solo el bloque de agua mas alto (la
     * superficie), no toda la columna, para que el hielo quede a ras del agua. Se busca
     * desde un poco por debajo de sus pies hasta por encima de su cabeza, porque si va
     * metido en el agua la superficie puede quedarle a media altura del cuerpo y hay que
     * llegar hasta ella.
     *
     * Solo se congela agua de fuente, igual que la espada, y nunca un bloque que ocupe el
     * propio coloso: se quedaria encajado dentro del hielo
     */
    private void congelarAguaAlrededor(final ServerLevel servidor) {
        final int mitad = LADO_CONGELADO_GLACIAROCK / 2;
        final BlockPos centro = BlockPos.containing(this.position());
        final int minY = (int) Math.floor(this.getBoundingBox().minY) - 2;
        final int maxY = (int) Math.floor(this.getBoundingBox().maxY) + 1;
        final AABB cajaColoso = this.getBoundingBox();

        for (int dx = -mitad; dx <= mitad; dx++) {
            for (int dz = -mitad; dz <= mitad; dz++) {
                // De arriba abajo: el primer agua de la columna es la superficie. Si justo
                // encima sigue habiendo agua, es que la superficie queda por encima del
                // rango de busqueda y esa columna no se toca
                for (int y = maxY; y >= minY; y--) {
                    final BlockPos pos = new BlockPos(centro.getX() + dx, y, centro.getZ() + dz);
                    if (!servidor.getFluidState(pos).is(Fluids.WATER)) {
                        continue;
                    }

                    if (!servidor.getFluidState(pos.above()).is(Fluids.WATER)
                            && !cajaColoso.intersects(new AABB(pos))) {
                        servidor.setBlock(pos, Blocks.ICE.defaultBlockState(), 3);
                    }
                    break;
                }
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
            // Si iba pilotado, el piloto sale despedido hacia arriba antes de reventar
            this.expulsarAlJinete(servidor);
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
    // entidad la aparta con el empuje normal en vez de dejarla pasar. Con su propio jinete
    // no choca: va DENTRO de su caja (el mismo detalle que cuida el bote, ver
    // Entity#isPassengerOfSameVehicle)
    @Override
    public boolean canCollideWith(final Entity entidad) {
        return !this.isPassengerOfSameVehicle(entidad)
            && (entidad instanceof Player || entidad.canBeCollidedWith(this));
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
