# Mod Armas de Hielo (`espadahielo`)

Mod de armas y magia de hielo para **Minecraft 1.26.2** con **Forge**.

- **Mod ID:** `espadahielo`
- **Versión de Minecraft:** 1.26.2
- **Forge:** 1.26.2-65.1.0
- **Java:** 25
- **Dependencia:** [GeckoLib](https://modrinth.com/mod/geckolib) 5.5.6 (obligatoria: la usa el Glaciarock)

Todo lo que aquí se describe está implementado y probado en partida.

---

## Contenido

| Elemento | Tipo | Cómo se consigue |
| --- | --- | --- |
| Espada de Hielo | Arma cuerpo a cuerpo | Crafteo |
| Cetro de Hielo | Arma a distancia / invocación | Crafteo |
| Bola de Hielo | Proyectil (interno) | Solo lo lanza el Cetro |
| Fuego Azul | Bloque (interno) | Nace de las explosiones de la Bola |
| Glaciarock | Coloso invocado | Solo con el Cetro |

---

## Espada de Hielo

Arma cuerpo a cuerpo con las estadísticas de una **espada de hierro** (250 de durabilidad, 6 de ataque, 1.6 de velocidad de ataque) y efectos de hielo.

**Crafteo** (patrón vertical: hielo, hielo, palo):

```
[ H ]   H = minecraft:ice
[ H ]   P = minecraft:stick
[ P ]
```

**Funciones:**

- **Golpe cuerpo a cuerpo:** aplica **Ralentización IV** (nivel 3) durante **5 segundos** al enemigo golpeado.
- **Clic izquierdo (a un bloque o al aire):** congela el agua / la zona delante de ti con un **patrón aleatorio** (3×2, 2×3, 3×3, 1×3, 3×1). Nunca se pone encima de ti y respeta la dirección a la que apuntas.
- **Clic derecho (mantener):** **rayo de hielo continuo**.
  - Sale de la punta de la espada y sigue tu mirada en línea recta.
  - Alcance: **15 bloques**.
  - Hace **2 de daño por segundo** al enemigo que engancha.
  - Aplica **congelado completo** (160 ticks: el enemigo queda helado del todo y empieza a sufrir daño de congelación).
  - Cadencia: un disparo cada medio segundo mientras mantienes pulsado.
  - Color turquesa claro, con **tinte/aurora azul-turquesa** en los mobs congelados.

---

## Cetro de Hielo

Arma a distancia invocadora. Durabilidad: **500**.

**Crafteo** (azul: hielo azul, lágrima de ghast, palo):

```
[ B L B ]   B = minecraft:blue_ice
[   P   ]   L = minecraft:ghast_tear
[   P   ]   P = minecraft:stick
```

**Funciones:**

### Clic izquierdo — Bola de Hielo
- Lanza una **bola de hielo** con el comportamiento de la bola de fuego del ghast: explota al tocar bloque o entidad.
- **Potencia de explosión 3** (la del ghast es 1) y **rompe bloques**.
- **6 de daño** directo al impacto contra una entidad viva.
- Congela a quien acierta durante 5 segundos.
- Congela el **agua en un radio de 6 bloques** alrededor del impacto (el agua sobrevive a la explosión, se congela justo después).
- Deja repartidos por el cráter hasta **6 mechones de Fuego Azul** (ver más abajo).
- Coste: **1 de durabilidad** por bola, con **medio segundo** de enfriamiento entre bola y bola (se ve el relojito en el HUD).

### Clic derecho — Invocar al Glaciarock
- Casteo de **3 segundos** (60 ticks) y el elemental aparece delante de tu mirada.
- Coste: **165 de durabilidad** (1/3 del cetro), **solo si el elemental sale con bien**.
  - Si sueltas el botón antes de tiempo, si te pegan o si te vas al aire/al agua, **no cuesta nada**.
  - Invocar **nunca rompe el cetro**: si el cobro lo dejaría a 0, se queda con 1.
- **No se puede castear en el aire ni nadando.**
- Si no hay durabilidad suficiente el conjuro ni empieza → *"Energía insuficiente para invocar al Glaciarock"*.
- Si al terminar el casteo no cabe en ningún sitio → *"No hay espacio suficiente para invocar al Glaciarock"*. El coloso necesita bastante sitio (su caja es de 4 × 7,25 × 4 bloques): se prueba delante de la mirada y, si no cabe, anillos completos a 4, 6 y 8 bloques.
- **Solo puede haber un Glaciarock vivo por jugador:** al invocar otro, el anterior desaparece.

### Clic derecho + agachado — Poseer al Glaciarock
- Si tienes un Glaciarock a menos de **64 bloques**, te **metes dentro de él** y lo pilotas.
- Si no tienes ninguno cerca → *"No tienes ningún Glaciarock cerca para poseer"*.
- Si el Glaciarock está demasiado herido (menos de 25 de vida) → *"El Glaciarock está demasiado herido para pilotarlo"*.
- Mientras pilotas, el cetro no dispara ni invoca.

---

## Glaciarock

Elemental de hielo colosal que invoca el Cetro. Modelado y animado con **GeckoLib**, escala **5×** (hitbox de **4 × 4 × 7,25 bloques**, que cubre el cuerpo pero no la envergadura de los brazos).

> El Glaciarock **no** aparece en el inventario creativo, no tiene huevo de invocación y no se puede desinvocar a mano: solo llega con el Cetro y solo se va al morir, al morir su dueño o al cambiar el dueño de dimensión.

**Estadísticas y comportamiento:**

- **200 de vida** (la del Ender Dragón) y resistencia total al empuje.
- **Golpe cuerpo a cuerpo de 30** (el doble que un gólem de hierro) con **3 bloques de alcance** desde el borde de su cuerpo.
  - El daño cae **al final** de la animación de ataque (1 segundo) y la cadencia también es de 1 segundo.
  - El golpe **manda al objetivo unos 10 bloques hacia atrás** y lo deja **ralentizado**.
- **Fuego y lava:** le hacen **×1,5 de daño** (es hielo, pero no es invulnerable: el fuego le hace *más* daño, no menos).
- **Detección y persecución:** detecta mobs hostiles hasta 30 bloques y persigue hasta 15.
- **Seguimiento del dueño (en calma):** te empieza a seguir cuando te alejas **20 bloques**, se para a **11** (no se te pega), y solo se **teletransporta** a tu lado si te vas a más de **40**. Con enemigos cerca manda el ataque: pelea pegado o lejos, da igual.
- **Clic derecho con la mano vacía (siendo el dueño):** se queda **quieto de guardia** donde está, con una **nube de copos de nieve** y un crujido de hielo para que lo notes. Otro clic derecho vuelve a moverse.
- **Congela el agua a su alrededor:** cuando está en el suelo hiela **la superficie del agua** en un cuadrado de **11 × 11** centrado en él (5 bloques hacia cada lado), cada 4 ticks. Solo agua de fuente, y sin helar los bloques que ocupa su propio cuerpo.
- **Pasos pesados:** sus zancadas suenan como estampidas (estilo T-Rex).
- **Salto:** salta como un caballo al máximo (fuerza 1.0). Sube los escalones normales andando y, cuando se topa con un repecho de 2-3 bloques que no sabe rodear, **se impulsa solo**.
- **Atrae la aggro:** los mobs hostiles a **16 bloques** le atacan a él como si fuera un jugador. Al que le pega le contesta, y a los que ya van a por su dueño no se los quita.
- **Muerte:** revienta como la bola de hielo pero con **potencia 9** (3 veces la de la bola), dejando un cráter de unos 12 bloques. Su dueño es inmune a esa explosión.
- Se domestica solo al jugador más cercano si aparece sin dueño (por ejemplo con `/summon`).

### Pilotar al Glaciarock (posesión)

Con el cetro en la mano, **agáchate y haz clic derecho** sobre un Glaciarock tuyo cercano: tu personaje desaparece dentro del coloso y pasas a **ser** él.

- **Moverse:** te mueves con las teclas normales.
- **Saltar:** con la barra espaciadora (salto de caballo, con un pequeño margen entre saltos).
- **Atacar:** **clic izquierdo**; pega a lo que tengas dentro del cono de tu mirada.
- **Salir:** vuelve a **agacharte** (Shift).
- **Cámara en primera persona:** ves los **brazos del Glaciarock**, se **oculta el HUD** del jugador y el arma que llevas, para que dé la sensación de ser el coloso. También se ven las piernas y los pies al mirar hacia abajo.
- **Barra de vida:** mientras lo pilotas (y también cuando es tu mascota normal) aparece arriba una **barra estilo jefe, turquesa**, con su vida numérica.
- **Expulsión por daño:** si al Glaciarock le quedan **25 de vida o menos**, te **expulsa solo** antes de que lo maten, para que no te pille su explosión. Mientras esté tan tocado no te deja volver a entrar.
- **Expulsión al morir:** si el coloso muere mientras lo pilotas, sales **despedido hacia arriba** con caída lenta, "como si salieses de un robot".

---

## Fuego Azul

Bloque que deja repartido la explosión de la Bola de Hielo. **No aparece en el inventario**: solo nace de las explosiones.

- Se ve y quema como el fuego común, pero es **azul cielo**.
- **Ralentiza** a quien lo toca.
- Da luz (nivel 15).
- A diferencia del fuego de almas, **no necesita un bloque que lo sostenga**.

---

## Mensajes en pantalla

| Mensaje | Cuándo aparece |
| --- | --- |
| "Energía insuficiente para invocar al Glaciarock" | No hay durabilidad suficiente para el casteo |
| "No hay espacio suficiente para invocar al Glaciarock" | Al terminar el casteo no cabe el coloso |
| "No tienes ningún Glaciarock cerca para poseer" | Clic derecho + agachado sin un Glaciarock a menos de 64 bloques |
| "El Glaciarock está demasiado herido para pilotarlo" | Intentas poseer un Glaciarock con 25 de vida o menos |

---

## Notas técnicas

- **GeckoLib es obligatorio.** La entidad `Glaciarock` es un `GeoEntity` con dos controladores de animación (`andar` y `ataque`) y sus assets viven en `assets/espadahielo/geckolib/`.
- El mod usa un **canal de red propio** (`espadahielo:main`) para avisar al servidor de los clics izquierdos al aire, que Minecraft no envía por defecto.
- La **barra de vida del Glaciarock** es una capa de HUD propia (no una boss bar de vanilla), porque el color turquesa no existe entre los colores de las boss bars.
- La **primera persona del Glaciarock** se resuelve marcando la entidad con un ticket de datos que hace que el renderizador oculte la cabeza y oriente los brazos, además de esconder el HUD y el ítem en mano.

---

*Estado actual: todo funcionando y probado en partida.*
