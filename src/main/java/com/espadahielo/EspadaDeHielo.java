package com.espadahielo;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// El id aqui debe coincidir con el de META-INF/mods.toml
@Mod(EspadaDeHielo.MODID)
public final class EspadaDeHielo {
    // Id del mod, definido en un unico lugar para todo el codigo
    public static final String MODID = "espadahielo";

    // Canal de red propio del mod. El cliente usa los paquetes para avisar al servidor
    // de los clics izquierdos al aire con cada arma, ya que Minecraft no manda nada al
    // servidor en ese caso (el evento LeftClickEmpty existe solo en el cliente)
    public static final SimpleChannel CANAL = ChannelBuilder.named(MODID + ":main")
        .networkProtocolVersion(2)
        .simpleChannel()
            .play()
                .serverbound()
                    .addMain(PaqueteGolpearAgua.class, PaqueteGolpearAgua.STREAM_CODEC, EspadaDeHielo::alGolpearAguaVacia)
                    .addMain(PaqueteDispararBola.class, PaqueteDispararBola.STREAM_CODEC, EspadaDeHielo::alDispararBolaAlAire)
        .build();

    // Duracion del debuff de ralentizacion al golpear (100 ticks = 5 segundos)
    public static final int TICKS_RALENTIZACION = 100;
    // Nivel del debuff: 0 = Ralentizacion I, 1 = II, 2 = III, 3 = IV (60% mas lento)...
    public static final int NIVEL_RALENTIZACION = 3;

    // --- Rayo de hielo (clic derecho) ---
    // Alcance del rayo, en bloques
    public static final double ALCANCE_RAYO_HIELO = 15.0;
    // Ticks de congelado que se aplican al enemigo alcanzado. El hielo se va derritiendo
    // 1 tick por tick, asi que el valor es, a la vez, cuantos ticks dura el congelado.
    // Vanilla considera "congelado del todo" a partir de 140 ticks
    // (Entity.BASE_TICKS_REQUIRED_TO_FREEZE), y por encima de eso empieza a hacer dano de
    // congelacion, asi que con 160 el enemigo queda helado del todo y empieza a sufrirlo.
    public static final int TICKS_CONGELADO_RAYO = 160;
    // Tiempo entre disparos mientras se mantiene pulsado el clic derecho (en ticks;
    // 10 = medio segundo). El primer disparo sale al pulsar y luego uno cada intervalo.
    // Cada disparo aplica el congelado, el sonido y gasta durabilidad
    public static final int TICKS_ENTRE_DISPAROS_RAYO = 10;
    // Cada cuantos ticks se vuelve a dibujar la linea del rayo mientras se mantiene pulsado.
    // Muy bajo (2 = cada decima de segundo) para que la linea este siempre ahi y no se vea
    // como una linea nueva que sale detras de otra
    public static final int TICKS_DIBUJO_RAYO = 2;
    // Cada cuantos ticks el rayo hace dano al enemigo que tiene enganchado (20 = 1 segundo)
    public static final int TICKS_DANIO_RAYO = 20;
    // Dano que aplica el rayo cada TICKS_DANIO_RAYO mientras se mantiene sobre el objetivo
    public static final float DANIO_RAYO_HIELO = 2.0F;
    // Separacion entre las particulas que dibujan la linea del rayo (en bloques). Cuanto mas
    // juntas, mas continua se ve la linea (que no parezca una fila de puntos sueltos)
    public static final double PASO_PARTICULAS_RAYO = 0.15;
    // Punto del que sale la LINEA del rayo respecto a los ojos del jugador, en bloques: a su
    // derecha, a la altura de la punta de la espada (que va mas arriba que la mano; valor
    // negativo = por encima de los ojos) y bastante por delante, que es donde queda la espada
    // sostenida en primera persona. Solo cambia el dibujo: el apuntado sigue saliendo de los
    // ojos, asi que el rayo da en el mismo sitio de siempre, pero las particulas ya no tapan
    // la vista
    public static final double SALIDA_RAYO_DERECHA = 0.72;
    public static final double SALIDA_RAYO_ABAJO = -0.05;
    public static final double SALIDA_RAYO_ADELANTE = 0.80;
    // Color del rayo, en formato 0xRRGGBB: turquesa muy claro (casi blanco azulado)
    public static final int COLOR_RAYO_HIELO = 0xC8FFF8;
    // Color de la aurora de hielo que envuelve al enemigo congelado (azul claro)
    public static final int COLOR_AURA_HIELO = 0xBFE8FF;
    // Tamano de cada particula del rayo. Ademas de agrandarlas o empequenecerlas, fija su
    // duracion: cuanto mas pequena, antes se desvanece (la linea desaparece mas rapido)
    public static final float TAMANO_PARTICULA_RAYO = 1.0F;

    // Registro diferido de los items del mod, bajo el namespace "espadahielo"
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    // --- Cetro de Hielo ---
    // Durabilidad total del cetro. Cada bola de hielo lanzada consume 1 (igual que una
    // flecha) y la invocacion del elemental consume un 33% de esta cantidad
    public static final int DURABILIDAD_CETRO = 500;

    // --- Bola de hielo (clic izquierdo del Cetro de Hielo) ---
    // Potencia de la explosion al impactar (la bola de fuego del ghast tiene 1)
    public static final int POTENCIA_EXPLOSION_BOLA = 3;
    // Dano directo del impacto contra la entidad acertada (igual que la bola del ghast)
    public static final float DANIO_IMPACTO_BOLA = 6.0F;
    // Ticks de congelado que aplica el impacto directo contra una entidad viva
    public static final int TICKS_CONGELADO_BOLA = 100;

    // La Espada de Hielo: mismas estadisticas que una espada de hierro (250 durabilidad,
    // 6 de ataque, 1.6 de velocidad de ataque) con efectos extra de hielo
    public static final RegistryObject<Item> ESPADA_DE_HIELO = ITEMS.register("espada_de_hielo",
        () -> new EspadaDeHieloItem(new Item.Properties().setId(ITEMS.key("espada_de_hielo")))
    );

    // El Cetro de Hielo: arma a distancia con bola de hielo explosiva e invocacion
    // del elemental de hielo
    public static final RegistryObject<Item> CETRO_DE_HIELO = ITEMS.register("cetro_de_hielo",
        () -> new CetroDeHieloItem(new Item.Properties().setId(ITEMS.key("cetro_de_hielo"))
            .durability(DURABILIDAD_CETRO))
    );

    // Item interno que pinta la bola de hielo en vuelo. No aparece en el inventario:
    // solo le da modelo y textura al proyectil (ver BolaDeHieloItem)
    public static final RegistryObject<Item> BOLA_DE_HIELO = ITEMS.register("bola_de_hielo",
        () -> new BolaDeHieloItem(new Item.Properties().setId(ITEMS.key("bola_de_hielo")))
    );

    // Registro diferido de las entidades del mod, bajo el namespace "espadahielo"
    public static final DeferredRegister<EntityType<?>> ENTIDADES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    // La bola de hielo: proyectil explosivo del cetro, con la misma logica de impacto
    // que la bola de fuego del ghast pero mas potente y congelando a quien acierta
    public static final RegistryObject<EntityType<BolaDeHielo>> ENTIDAD_BOLA_DE_HIELO = ENTIDADES.register("bola_de_hielo",
        () -> EntityType.Builder.<BolaDeHielo>of(BolaDeHielo::new, MobCategory.MISC)
            .noLootTable()
            .sized(1.0F, 1.0F)
            .clientTrackingRange(10)
            .updateInterval(10)
            .build(ENTIDADES.key("bola_de_hielo"))
    );

    public EspadaDeHielo(FMLJavaModLoadingContext context) {
        var modBusGroup = context.getModBusGroup();

        // Registrar el DeferredRegister en el bus de eventos del mod
        ITEMS.register(modBusGroup);
        ENTIDADES.register(modBusGroup);

        // Anadir la espada a la pestana de Combate del inventario creativo
        BuildCreativeModeTabContentsEvent.BUS.addListener(EspadaDeHielo::addCreative);

        // Congelado del agua al golpearla con la espada (clic izquierdo sobre un bloque).
        // En eventbus 7 la cancelacion es el valor de retorno del listener:
        // true => el clic se cancela (solo cuando la espada apunta al agua)
        PlayerInteractEvent.LeftClickBlock.BUS.addListener(EspadaDeHielo::alGolpearAgua);

        // Golpear el aire (sin bloque solido a la vista, p. ej. apuntando al agua profunda
        // desde la superficie). Este evento es solo cliente: le avisamos al servidor
        PlayerInteractEvent.LeftClickEmpty.BUS.addListener(EspadaDeHielo::alGolpearAlAire);

        // Bola de hielo: clic izquierdo con el Cetro de Hielo apuntando a un bloque.
        // Se cancela el clic (el cetro no sirve para minar) y el servidor lanza la bola
        PlayerInteractEvent.LeftClickBlock.BUS.addListener(EspadaDeHielo::alGolpearConCetro);

        // Bola de hielo: clic izquierdo al aire con el Cetro de Hielo. Solo cliente:
        // le avisamos al servidor con el propio paquete
        PlayerInteractEvent.LeftClickEmpty.BUS.addListener(EspadaDeHielo::alGolpearAlAireConCetro);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ESPADA_DE_HIELO);
            event.accept(CETRO_DE_HIELO);
        }
    }

    // Al golpear con la Espada de Hielo en la mano principal (clic izquierdo que acierta a un
    // bloque) se actua sobre el fluido apuntado: se congela en area si es agua y se evapora
    // si es lava.
    // Listener Predicate: devolver true cancela el clic (evita minar el fondo del charco).
    // (Va aqui y no en EventosMod porque la clase anotada con @EventBusSubscriber
    // exige @SubscribeEvent en todos sus metodos estaticos con un evento como parametro)
    private static boolean alGolpearAgua(final PlayerInteractEvent.LeftClickBlock event) {
        // Solo al pulsar el clic; mientras se mantiene pulsado el evento se repite
        // (CLIENT_HOLD) y no queremos congelar cada tick
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return false;
        }

        final var stack = event.getEntity().getMainHandItem();
        if (!(stack.getItem() instanceof EspadaDeHieloItem espada)) {
            return false;
        }

        return espada.intentarGolpeDeEspada(event.getLevel(), event.getEntity(), stack,
            event.getEntity().getXRot(), event.getEntity().getYRot());
    }

    // Al golpear el aire con la Espada de Hielo en la mano principal. Solo se ejecuta en
    // el cliente: le manda al servidor los angulos de mira para que el congele el agua (o
    // evapore la lava) mas cercana en esa direccion (el cliente no modifica el mundo por
    // si mismo)
    private static void alGolpearAlAire(final PlayerInteractEvent.LeftClickEmpty event) {
        if (!(event.getEntity().getMainHandItem().getItem() instanceof EspadaDeHieloItem)) {
            return;
        }

        CANAL.send(
            new PaqueteGolpearAgua(event.getEntity().getXRot(), event.getEntity().getYRot()),
            PacketDistributor.SERVER.noArg()
        );
    }

    // El servidor recibe el aviso de golpe al aire y congela o evapora (hilo principal via
    // addMain). Valida que el emisor exista y que los angulos sean numeros sanos antes de
    // usarlos
    private static void alGolpearAguaVacia(final PaqueteGolpearAgua paquete, final CustomPayloadEvent.Context contexto) {
        final var jugador = contexto.getSender();
        if (jugador == null || !Float.isFinite(paquete.xRot()) || !Float.isFinite(paquete.yRot())) {
            return;
        }

        final var stack = jugador.getMainHandItem();
        if (!(stack.getItem() instanceof EspadaDeHieloItem espada)) {
            return;
        }

        espada.intentarGolpeDeEspada(jugador.level(), jugador, stack, paquete.xRot(), paquete.yRot());
    }

    // Al golpear un bloque con el Cetro de Hielo en la mano principal se lanza una bola
    // de hielo hacia donde mira el jugador y se cancela el clic (el cetro no sirve para
    // minar). Mientras se mantiene pulsado el boton el evento se repite (CLIENT_HOLD),
    // pero la bola solo sale al pulsar (START), como un disparo por clic.
    // Listener Predicate: devolver true cancela el clic
    private static boolean alGolpearConCetro(final PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity().getMainHandItem().getItem() instanceof CetroDeHieloItem cetro)) {
            return false;
        }

        // El cliente solo cancela el minado; la bola la lanza el servidor
        if (event.getLevel().isClientSide()) {
            return true;
        }

        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return true;
        }

        return cetro.intentarDisparo(event.getLevel(), event.getEntity(),
            event.getEntity().getXRot(), event.getEntity().getYRot());
    }

    // Al golpear el aire con el Cetro de Hielo en la mano principal. Solo se ejecuta en
    // el cliente: le manda al servidor los angulos de mira para que lance la bola de
    // hielo en esa direccion (el cliente no modifica el mundo por si mismo)
    private static void alGolpearAlAireConCetro(final PlayerInteractEvent.LeftClickEmpty event) {
        if (!(event.getEntity().getMainHandItem().getItem() instanceof CetroDeHieloItem)) {
            return;
        }

        CANAL.send(
            new PaqueteDispararBola(event.getEntity().getXRot(), event.getEntity().getYRot()),
            PacketDistributor.SERVER.noArg()
        );
    }

    // El servidor recibe el aviso de golpe al aire con el cetro y lanza la bola de hielo
    // (hilo principal via addMain). Valida que el emisor exista y que los angulos sean
    // numeros sanos antes de usarlos
    private static void alDispararBolaAlAire(final PaqueteDispararBola paquete, final CustomPayloadEvent.Context contexto) {
        final var jugador = contexto.getSender();
        if (jugador == null || !Float.isFinite(paquete.xRot()) || !Float.isFinite(paquete.yRot())) {
            return;
        }

        if (!(jugador.getMainHandItem().getItem() instanceof CetroDeHieloItem cetro)) {
            return;
        }

        cetro.intentarDisparo(jugador.level(), jugador, paquete.xRot(), paquete.yRot());
    }
}
