package com.espadahielo;

import net.minecraft.core.registries.Registries;
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

    // Canal de red propio del mod. El cliente usa el paquete para avisar al servidor
    // cuando se golpea el aire con la espada, ya que Minecraft no manda nada al servidor
    // en ese caso (el evento LeftClickEmpty existe solo en el cliente)
    public static final SimpleChannel CANAL = ChannelBuilder.named(MODID + ":main")
        .networkProtocolVersion(1)
        .simpleChannel()
            .play()
                .serverbound()
                    .addMain(PaqueteGolpearAgua.class, PaqueteGolpearAgua.STREAM_CODEC, EspadaDeHielo::alGolpearAguaVacia)
        .build();

    // Duracion del debuff de ralentizacion al golpear (100 ticks = 5 segundos)
    public static final int TICKS_RALENTIZACION = 100;
    // Nivel del debuff: 0 = Ralentizacion I, 1 = II, 2 = III, 3 = IV (60% mas lento)...
    public static final int NIVEL_RALENTIZACION = 3;

    // Registro diferido de los items del mod, bajo el namespace "espadahielo"
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    // La Espada de Hielo: mismas estadisticas que una espada de hierro (250 durabilidad,
    // 6 de ataque, 1.6 de velocidad de ataque) con efectos extra de hielo
    public static final RegistryObject<Item> ESPADA_DE_HIELO = ITEMS.register("espada_de_hielo",
        () -> new EspadaDeHieloItem(new Item.Properties().setId(ITEMS.key("espada_de_hielo")))
    );

    public EspadaDeHielo(FMLJavaModLoadingContext context) {
        var modBusGroup = context.getModBusGroup();

        // Registrar el DeferredRegister en el bus de eventos del mod
        ITEMS.register(modBusGroup);

        // Anadir la espada a la pestana de Combate del inventario creativo
        BuildCreativeModeTabContentsEvent.BUS.addListener(EspadaDeHielo::addCreative);

        // Congelado del agua al golpearla con la espada (clic izquierdo sobre un bloque).
        // En eventbus 7 la cancelacion es el valor de retorno del listener:
        // true => el clic se cancela (solo cuando la espada apunta al agua)
        PlayerInteractEvent.LeftClickBlock.BUS.addListener(EspadaDeHielo::alGolpearAgua);

        // Golpear el aire (sin bloque solido a la vista, p. ej. apuntando al agua profunda
        // desde la superficie). Este evento es solo cliente: le avisamos al servidor
        PlayerInteractEvent.LeftClickEmpty.BUS.addListener(EspadaDeHielo::alGolpearAlAire);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ESPADA_DE_HIELO);
        }
    }

    // Al golpear el agua con la Espada de Hielo en la mano principal (clic izquierdo que
    // acierta a un bloque), congela en area los bloques de agua del punto apuntado.
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

        return espada.intentarCongelar(event.getLevel(), event.getEntity(), stack,
            event.getEntity().getXRot(), event.getEntity().getYRot());
    }

    // Al golpear el aire con la Espada de Hielo en la mano principal. Solo se ejecuta en
    // el cliente: le manda al servidor los angulos de mira para que el congele el agua mas
    // cercana en esa direccion (el cliente no modifica el mundo por si mismo)
    private static void alGolpearAlAire(final PlayerInteractEvent.LeftClickEmpty event) {
        if (!(event.getEntity().getMainHandItem().getItem() instanceof EspadaDeHieloItem)) {
            return;
        }

        CANAL.send(
            new PaqueteGolpearAgua(event.getEntity().getXRot(), event.getEntity().getYRot()),
            PacketDistributor.SERVER.noArg()
        );
    }

    // El servidor recibe el aviso de golpe al aire y congela (hilo principal via addMain).
    // Valida que el emisor exista y que los angulos sean numeros sanos antes de usarlos
    private static void alGolpearAguaVacia(final PaqueteGolpearAgua paquete, final CustomPayloadEvent.Context contexto) {
        final var jugador = contexto.getSender();
        if (jugador == null || !Float.isFinite(paquete.xRot()) || !Float.isFinite(paquete.yRot())) {
            return;
        }

        final var stack = jugador.getMainHandItem();
        if (!(stack.getItem() instanceof EspadaDeHieloItem espada)) {
            return;
        }

        espada.intentarCongelar(jugador.level(), jugador, stack, paquete.xRot(), paquete.yRot());
    }
}
