package com.espadahielo;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
}
