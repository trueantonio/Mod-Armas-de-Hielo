package com.espadahielo;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

// Renderizadores de las entidades del mod y barra de casteo del cetro. Solo se registra en
// cliente: las clases de renderizado no existen en el servidor. En Forge 65 los eventos
// EntityRenderersEvent viven en el bus por defecto, asi que no se pone el param bus (el
// default ya es el correcto para ellos)
@Mod.EventBusSubscriber(modid = EspadaDeHielo.MODID, value = Dist.CLIENT)
public final class ClienteMod {
    // Nombre con el que se registra la barra de casteo en la lista de capas del HUD (tiene
    // que ser unico dentro de su pila)
    private static final Identifier CAPA_BARRA_CONJURO =
        Identifier.fromNamespaceAndPath(EspadaDeHielo.MODID, "barra_conjuro_glaciarock");

    // Barra de progreso del conjuro: alto y ancho en pixeles de GUI, y cuantos pixeles por
    // encima del borde inferior de la pantalla se dibuja. La linea del mensaje de accion
    // (por donde salen los avisos de "energia insuficiente" y "sin espacio") ocupa de la
    // altura 72 a la 63 y el nombre del item seleccionado empieza en la 59, asi que la
    // barra se dibuja justo en el hueco de en medio: los avisos quedan encima de ella y no
    // tapa el nombre del item
    private static final int ANCHO_BARRA_CONJURO = 120;
    private static final int ALTO_BARRA_CONJURO = 4;
    private static final int ALTURA_SOBRE_EL_HOTBAR = 62;
    // Colores de la barra (ARGB): fondo oscuro, borde mas oscuro, relleno verde con una
    // linea clara arriba y marcas cada medio segundo
    private static final int COLOR_FONDO_BARRA = 0xB00A1F2B;
    private static final int COLOR_BORDE_BARRA = 0xFF05131A;
    private static final int COLOR_RELLENO_BARRA = 0xFF3CE06A;
    private static final int COLOR_BRILLO_BARRA = 0xFFB6FFD0;
    private static final int COLOR_MARCA_BARRA = 0x70000000;
    // Cada cuantos ticks se dibuja una marca dentro de la barra (10 = medio segundo)
    private static final int TICKS_POR_MARCA = 10;

    // Barra de vida del Glaciarock. Se cuelga directamente de la raiz del HUD (y no de la
    // pila del hotbar, que se oculta con F1) para que siga viendose al pilotar el coloso,
    // que es justo cuando el resto del HUD esta escondido
    private static final Identifier CAPA_BARRA_VIDA_GLACIAROCK =
        Identifier.fromNamespaceAndPath(EspadaDeHielo.MODID, "barra_vida_glaciarock");

    // Medidas y colores de la barra (ARGB), al estilo de la barra de jefe del dragon del
    // End: ancho y alto en pixeles de GUI, distancia al borde superior de la pantalla y
    // el turquesa del hielo con una linea clara arriba y una sombra abajo
    private static final int ANCHO_BARRA_VIDA = 182;
    private static final int ALTO_BARRA_VIDA = 5;
    private static final int ALTURA_BARRA_VIDA = 12;
    private static final int COLOR_BORDE_VIDA = 0xFF04141A;
    private static final int COLOR_FONDO_VIDA = 0xFF14262B;
    private static final int COLOR_RELLENO_VIDA = 0xFF2EE6D6;
    private static final int COLOR_BRILLO_VIDA = 0xFFB6FFF7;
    private static final int COLOR_SOMBRA_VIDA = 0xFF12A99C;

    // Tick del ultimo puñetazo pedido desde dentro del Glaciarock. El clic izquierdo se
    // repite cada tick mientras se mantiene pulsado (atacando o minando) y al coloso hay
    // que pedirle un golpe por segundo como mucho, que es lo que dura su animacion
    private static int ultimoGolpeDeJinete = -1000;

    // Mientras se pilota el Glaciarock se esconde el HUD del jugador para que la vista sea
    // la del coloso. Se apunta si el HUD ya venia escondido de antes (tecla F1) para
    // devolverlo exactamente como estaba al bajar
    private static boolean hudEscondidoPorPosesion = false;
    private static boolean hudEstabaEscondidoAntes = false;

    // La capa del HUD se anade a mano porque AddGuiOverlayLayersEvent tiene su propio bus
    // (no es de los que recogen los @SubscribeEvent). Se hace en un bloque estatico porque
    // Forge ya carga esta clase al escanear los @EventBusSubscriber de cliente, mucho antes
    // de que se componga el HUD
    //
    // El listener va como lambda a proposito: el registro de los @EventBusSubscriber usa
    // comprobaciones estrictas en el entorno de desarrollo (la propiedad de sistema
    // eventbus.api.strictRuntimeChecks, que ForgeGradle activa en runClient) y rechaza con
    // "Missing @SubscribeEvent annotation" CUALQUIER metodo declarado en esta clase que
    // reciba un evento como primer parametro, sea publico o privado. Los cuerpos de las
    // lambdas se compilan como metodos sinteticos y esos quedan fuera de la revision, asi
    // que aqui no hace falta anotar nada
    static {
        // La barra se cuelga justo por encima del nombre del item seleccionado, o sea por
        // encima del hotbar y de sus adornos, en la misma pila del HUD que ellos (asi
        // desaparece con F1 como el resto)
        AddGuiOverlayLayersEvent.BUS.addListener(evento -> evento.getLayeredDraw().addAbove(
            ForgeLayeredDraw.HOTBAR_AND_DECOS,
            CAPA_BARRA_CONJURO,
            ForgeLayeredDraw.SELECTED_ITEM_NAME,
            ClienteMod::dibujarBarraDeConjuro));

        // La barra de vida del Glaciarock cuelga directamente de la raiz del HUD, no de la
        // pila del hotbar: asi se sigue viendo cuando el HUD esta escondido (al pilotar el
        // coloso). Al ir la ultima de la lista se dibuja por encima del resto de capas
        AddGuiOverlayLayersEvent.BUS.addListener(evento -> evento.getLayeredDraw().add(
            CAPA_BARRA_VIDA_GLACIAROCK,
            ClienteMod::dibujarBarraDeVidaGlaciarock));

        // Con el clic izquierdo dentro del Glaciarock pasa lo contrario: no pega el jugador,
        // pega el coloso. Minecraft lo manda como un ataque normal (y, si el punto de mira
        // acaba en un bloque, ademas se pone a minar), asi que se cancela el clic y se le
        // pide el puñetazo al servidor con el paquete del mod. Como el evento se repite
        // cada tick mientras se mantiene pulsado, los avisos se espacian lo que dura la
        // animacion de golpe del coloso
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(evento -> {
            if (!evento.isAttack()) {
                return false;
            }

            final Player jugador = Minecraft.getInstance().player;
            if (jugador == null || !(jugador.getVehicle() instanceof Glaciarock)) {
                return false;
            }

            // Que el jugador no de la patada al aire: el que pega es el coloso
            evento.setSwingHand(false);

            final int ahora = jugador.tickCount;
            // El contador del jugador vuelve a cero al entrar en un mundo nuevo (y al
            // reaparecer): si va por detras del ultimo golpe es que es otro jugador, asi
            // que se toma como que ya toca pegar
            if (ahora < ultimoGolpeDeJinete
                    || ahora - ultimoGolpeDeJinete >= Glaciarock.TICKS_ATAQUE_GLACIAROCK) {
                ultimoGolpeDeJinete = ahora;
                EspadaDeHielo.CANAL.send(new PaqueteAtaqueGlaciarock(),
                    PacketDistributor.SERVER.noArg());
            }

            // El clic se cancela siempre: desde dentro no se mina ni se pega a mano
            return true;
        });

        // Dentro del Glaciarock no se dibuja el brazo ni el arma del jugador en primera
        // persona: los brazos que se ven son los del propio coloso (ver GlaciarockRenderer)
        RenderHandEvent.BUS.addListener(evento -> {
            final Player jugador = Minecraft.getInstance().player;
            return jugador != null && jugador.getVehicle() instanceof Glaciarock;
        });

        // Mientras se pilota el coloso se esconde el HUD del jugador (corazones, hambre,
        // armadura, hotbar, experiencia y punto de mira), que es justo lo que hace la tecla
        // F1, para que la pantalla quede limpia como la de una maquina. Al bajar se
        // devuelve a como estaba (si el jugador ya lo tenia escondido, se respeta)
        TickEvent.ClientTickEvent.Post.BUS.addListener(evento -> {
            final Minecraft cliente = Minecraft.getInstance();
            final boolean pilotando = cliente.player != null
                && cliente.player.getVehicle() instanceof Glaciarock;

            if (pilotando) {
                if (!hudEscondidoPorPosesion) {
                    hudEscondidoPorPosesion = true;
                    hudEstabaEscondidoAntes = cliente.gui.hud.isHidden();
                }

                // Si el jugador pulsa F1 mientras pilota, se vuelve a esconder
                if (!cliente.gui.hud.isHidden()) {
                    cliente.gui.hud.toggle();
                }
            } else if (hudEscondidoPorPosesion) {
                hudEscondidoPorPosesion = false;

                if (!hudEstabaEscondidoAntes && cliente.gui.hud.isHidden()) {
                    cliente.gui.hud.toggle();
                }
            }
        });
    }

    private ClienteMod() {
    }

    // La bola de hielo se dibuja como un item girando en el aire (el mismo sistema que
    // usa la bola de fuego del ghast desde 26.2, ThrownItemRenderer + ItemSupplier),
    // a escala 3 y con luz maxima para que brille como aquella
    @SubscribeEvent
    public static void alRegistrarRenderizadores(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EspadaDeHielo.ENTIDAD_BOLA_DE_HIELO.get(),
            contexto -> new ThrownItemRenderer<>(contexto, 3.0F, true));

        // El Glaciarock es un GeoEntity de GeckoLib: el renderizador solo aporta el
        // contexto y el tipo de entidad (los modelos y animaciones los busca GeckoLib
        // solo con el nombre de la entidad, ver GlaciarockRenderer)
        event.registerEntityRenderer(EspadaDeHielo.ENTIDAD_GLACIAROCK.get(), GlaciarockRenderer::new);
    }

    /**
     * Barra de progreso del casteo del Glaciarock: una barra horizontal de ticks que se
     * va llenando de verde mientras el jugador local aguanta el clic derecho con el cetro.
     *
     * No hay estado propio que llevar: todo sale del uso del item, que va sincronizado
     * desde el servidor. Cuando el servidor cancela el conjuro (dano, aire, agua) o lo
     * termina, apaga ese estado y la barra desaparece sola
     */
    private static void dibujarBarraDeConjuro(final GuiGraphicsExtractor graficos,
                                              final DeltaTracker delta) {
        final Player jugador = Minecraft.getInstance().player;
        if (jugador == null || !jugador.isUsingItem()
                || !(jugador.getUseItem().getItem() instanceof CetroDeHieloItem)) {
            return;
        }

        final int total = EspadaDeHielo.TICKS_CONJURO_GLACIAROCK;
        final int transcurridos = Mth.clamp(jugador.getTicksUsingItem(), 0, total);
        if (transcurridos <= 0) {
            return;
        }

        final int x = (graficos.guiWidth() - ANCHO_BARRA_CONJURO) / 2;
        final int y = graficos.guiHeight() - ALTURA_SOBRE_EL_HOTBAR;

        graficos.nextStratum();

        // Borde y fondo de la barra
        graficos.fill(x - 1, y - 1, x + ANCHO_BARRA_CONJURO + 1, y + ALTO_BARRA_CONJURO + 1,
            COLOR_BORDE_BARRA);
        graficos.fill(x, y, x + ANCHO_BARRA_CONJURO, y + ALTO_BARRA_CONJURO, COLOR_FONDO_BARRA);

        // Relleno verde hasta el tick en el que va el casteo, con una linea clara arriba
        final int relleno = transcurridos * ANCHO_BARRA_CONJURO / total;
        if (relleno > 0) {
            graficos.fill(x, y, x + relleno, y + ALTO_BARRA_CONJURO, COLOR_RELLENO_BARRA);
            graficos.fill(x, y, x + relleno, y + 1, COLOR_BRILLO_BARRA);
        }

        // Marcas cada medio segundo: asi se ve avanzar la barra "a tirones" de ticks
        for (int marca = TICKS_POR_MARCA; marca < total; marca += TICKS_POR_MARCA) {
            final int xMarca = x + marca * ANCHO_BARRA_CONJURO / total;
            graficos.fill(xMarca, y, xMarca + 1, y + ALTO_BARRA_CONJURO, COLOR_MARCA_BARRA);
        }
    }

    /**
     * Glaciarock que le toca a ese jugador en la barra de vida: el que este pilotando (si
     * va dentro de el) o, si no, el suyo que tenga mas cerca de entre los cargados. Null si
     * no tiene ninguno a la vista
     */
    private static Glaciarock glaciarockDeLaBarraDeVida(final Player jugador) {
        if (jugador.getVehicle() instanceof Glaciarock coloso && coloso.isAlive()) {
            return coloso;
        }

        if (!(jugador.level() instanceof ClientLevel nivel)) {
            return null;
        }

        Glaciarock mejor = null;
        double mejorDistancia = Double.MAX_VALUE;
        for (final Entity entidad : nivel.entitiesForRendering()) {
            if (entidad instanceof Glaciarock coloso && coloso.isAlive()
                    && coloso.isOwnedBy(jugador)) {
                final double distancia = jugador.distanceToSqr(coloso);
                if (distancia < mejorDistancia) {
                    mejorDistancia = distancia;
                    mejor = coloso;
                }
            }
        }

        return mejor;
    }

    /**
     * Barra de vida del Glaciarock, al estilo de la barra de jefe del dragon del End pero
     * en turquesa. Se ve tanto si el coloso es tu mascota y anda suelto como si lo estas
     * pilotando: como esta capa cuelga de la raiz del HUD, sigue dibujandose aunque el
     * resto del HUD este escondido (que es lo que pasa al pilotarlo). Fuera de la posesion
     * se respeta el F1
     */
    private static void dibujarBarraDeVidaGlaciarock(final GuiGraphicsExtractor graficos,
                                                     final DeltaTracker delta) {
        final Minecraft cliente = Minecraft.getInstance();
        final Player jugador = cliente.player;
        if (jugador == null) {
            return;
        }

        final Glaciarock coloso = glaciarockDeLaBarraDeVida(jugador);
        if (coloso == null) {
            return;
        }

        if (cliente.gui.hud.isHidden() && !(jugador.getVehicle() instanceof Glaciarock)) {
            return;
        }

        final float vidaMaxima = Math.max(1.0F, coloso.getMaxHealth());
        final float vida = Mth.clamp(coloso.getHealth(), 0.0F, vidaMaxima);
        final float fraccion = vida / vidaMaxima;

        final int x = (graficos.guiWidth() - ANCHO_BARRA_VIDA) / 2;
        final int y = ALTURA_BARRA_VIDA;

        graficos.nextStratum();

        // Borde, fondo y relleno turquesa con una linea clara arriba y una sombra abajo
        graficos.fill(x - 1, y - 1, x + ANCHO_BARRA_VIDA + 1, y + ALTO_BARRA_VIDA + 1,
            COLOR_BORDE_VIDA);
        graficos.fill(x, y, x + ANCHO_BARRA_VIDA, y + ALTO_BARRA_VIDA, COLOR_FONDO_VIDA);

        final int relleno = Mth.ceil(fraccion * ANCHO_BARRA_VIDA);
        if (relleno > 0) {
            graficos.fill(x, y, x + relleno, y + ALTO_BARRA_VIDA, COLOR_RELLENO_VIDA);
            graficos.fill(x, y, x + relleno, y + 1, COLOR_BRILLO_VIDA);
            graficos.fill(x, y + ALTO_BARRA_VIDA - 1, x + relleno, y + ALTO_BARRA_VIDA,
                COLOR_SOMBRA_VIDA);
        }

        // Nombre del coloso y su vida numerica, centrados justo encima de la barra
        final Component texto = coloso.getDisplayName().copy()
            .append(Component.literal("  " + Mth.ceil(vida) + "/" + Mth.ceil(vidaMaxima)));
        final int anchoTexto = cliente.font.width(texto);
        graficos.text(cliente.font, texto, (graficos.guiWidth() - anchoTexto) / 2, y - 10, -1);
    }
}
