# Game Service

> Motor de partida de UNO en tiempo real + power-ups + chat, todo sobre un único WebSocket —
> el corazón del sistema de **UNO Power-Ups Arena**.

[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Tests](https://img.shields.io/badge/tests-41%20passing-success)]()
[![License](https://img.shields.io/badge/license-MIT-blue)]()

---

## Tabla de contenidos

1. [Descripción](#1-descripción)
2. [Arquitectura](#2-arquitectura)
3. [Tecnologías](#3-tecnologías)
4. [Requisitos](#4-requisitos)
5. [Instalación](#5-instalación)
6. [Configuración](#6-configuración)
7. [Ejecución](#7-ejecución)
8. [Estructura del proyecto](#8-estructura-del-proyecto)
9. [Flujo interno](#9-flujo-interno)
10. [API REST interna](#10-api-rest-interna)
11. [WebSocket / STOMP](#11-websocket--stomp)
12. [Power-ups (HU-04)](#12-power-ups-hu-04)
13. [Reconexión (HU-08)](#13-reconexión-hu-08)
14. [Modelo en memoria y concurrencia](#14-modelo-en-memoria-y-concurrencia)
15. [Seguridad](#15-seguridad)
16. [Logs](#16-logs)
17. [Observabilidad y KPIs (HU-11)](#17-observabilidad-y-kpis-hu-11)
18. [Testing](#18-testing)
19. [Despliegue](#19-despliegue)
20. [Solución de problemas](#20-solución-de-problemas)
21. [Roadmap](#21-roadmap)
22. [Contribución](#22-contribución)
23. [Licencia](#23-licencia)
24. [Autor](#24-autor)

---

## 1. Descripción

**Game Service** es el único servicio que sabe jugar UNO. Recibe el roster inicial de
[Lobby Service](../LobbyService), reparte cartas, valida cada jugada, aplica los efectos de las
cartas especiales y de los 5 power-ups, gestiona reconexiones y publica el estado de la partida
en tiempo real por WebSocket. También aloja el chat de sala.

Es el **único** servicio del sistema con el que el cliente habla por WebSocket — y lo hace
**directamente**, sin pasar por el [API Gateway](../APIGATEWAY), para minimizar la latencia
percibida en cada jugada (EC-01 del Inception doc).

### Historias de usuario que implementa

| HU | Descripción |
|---|---|
| **HU-03** | Jugar una partida completa de UNO (reglas estándar: saltar, reversa, +2, comodín, +4) |
| **HU-04** | Power-ups aleatorios capturables (robo forzado, espía, salto selectivo, duplicador, escudo) |
| **HU-05** | Chat en tiempo real por sala |
| **HU-08** | Reconexión: un jugador que pierde la conexión tiene una ventana de tiempo antes de ser marcado como abandonado |
| **HU-11** | KPIs de negocio expuestos vía Micrometer/Prometheus |

### Por qué WebSocket directo (sin Gateway)

El Gateway sigue validando el JWT para **todo el tráfico REST** (`/api/**`). Pero una partida de
UNO puede generar decenas de mensajes por minuto (jugadas, robos, chat) y cada salto de red extra
se siente en la UI. Por eso el WebSocket (`/ws-game`) se conecta **directo** al Game Service, y la
autenticación se revalida aquí mismo en el `CONNECT` (ver [§15](#15-seguridad)) — el servicio no
puede asumir que "ya pasó por el Gateway" como sí puede asumirlo Lobby Service con las cabeceras
`X-User-Id`/`X-Username`.

---

## 2. Arquitectura

```
                                REST (JWT vía Gateway)
   Internet ──▶ API Gateway ──────────────────────────▶ (no toca Game Service para juego)

                                WebSocket directo (JWT revalidado en CONNECT)
   Internet ─────────────────────────────────────────▶ Game Service :8083 (/ws-game)

                    HTTP interno + X-Internal-Key                HTTP interno + X-Internal-Key
   Lobby Service ──────────────────────────────────▶ Game Service ──────────────────────▶ User Service
   (iniciar partida, roster)                                      (notificar resultado al terminar)
```

**Capas internas:**

```
STOMP Controllers (ChatController, PartidaWebSocketController)
REST Controllers internos (PartidaInternalController, RosterController)
        │
        ▼
PartidaService  (orquesta: registry + broadcast + metricas + notificacion a UserService)
        │
        ▼
PartidaRegistry (ConcurrentHashMap<codigo, Partida> — estado en memoria, no hay tabla de partidas en BD)
        │
        ▼
Partida (aggregate root, motor de reglas, metodos "synchronized")
```

**Decisión de diseño clave:** Game Service **no usa base de datos**. Una partida vive solo en
memoria del proceso mientras está `EN_CURSO`; al terminar, solo se persiste el *resultado* (vía
`UserServiceClient` hacia User Service, que sí tiene tabla `usuarios` con contadores). Esto es
deliberado: el estado de una partida activa es efímero por naturaleza y evita el costo de
serializar cada jugada a disco. La contrapartida (documentada como deuda técnica) es que si el
proceso de Game Service se reinicia, las partidas en curso se pierden — aceptable para el alcance
del proyecto, ver [§21 Roadmap](#21-roadmap).

---

## 3. Tecnologías

| Tecnología | Versión | Uso |
|---|---|---|
| Java | 21 | Lenguaje |
| Spring Boot | 3.2.5 | Framework |
| Spring WebSocket + STOMP | incluido en Boot 3.2.5 | `/ws-game`, brokers `/topic` y `/queue` |
| SockJS (servidor) | incluido en `spring-websocket` | Fallback para navegadores/proxies sin WS nativo |
| jjwt | 0.12.6 | Verificación del JWT en el `CONNECT` STOMP |
| Micrometer + Actuator | incluido en Boot 3.2.5 | Métricas de negocio (KPIs) y técnicas |
| Spring Web (`RestClient`) | incluido en Boot 3.2.5 | Llamada interna a User Service al terminar la partida |
| JUnit 5 + Mockito | incluido en Boot 3.2.5 | Testing |
| JaCoCo | 0.8.12 | Cobertura (95% en `model/`) |
| Maven | 3.9+ | Build |
| Docker | multi-stage | Empaquetado |

---

## 4. Requisitos

- **Java 21**.
- **Maven 3.9+**.
- **User Service corriendo** (para notificar resultados al terminar una partida; si no está
  disponible, la partida termina igual mostrando el ganador — la notificación de stats se pierde y
  queda logueada como warning, no bloquea el fin de partida).
- **No requiere PostgreSQL.**
- **Docker** (opcional).

---

## 5. Instalación

```bash
git clone https://github.com/UNO-POWER/GameService.git
cd GameService
java -version      # confirmar Java 21
mvn -q -DskipTests package
mvn test
```

---

## 6. Configuración

| Variable | Obligatoria en prod | Valor por defecto (dev) | Descripción |
|---|---|---|---|
| `JWT_SECRET` | ✅ | secreto de ejemplo | Debe ser **idéntico** al del Gateway y User Service — este servicio valida el JWT por su cuenta en el `CONNECT` |
| `INTERNAL_API_KEY` | ✅ | clave de ejemplo | Enviada como `X-Internal-Key` al llamar a User Service; también valida las llamadas entrantes de Lobby Service |
| `USER_SERVICE_URL` | ✅ | `http://localhost:8081` | Para notificar resultado (`partidasJugadas`/`partidasGanadas`/racha) |
| `CORS_ALLOWED_ORIGINS` | ✅ | `http://localhost:5173` | Orígenes permitidos para el handshake WebSocket (lista separada por comas) |
| `RECONEXION_TIMEOUT_SEGUNDOS` | ❌ | `60` | Segundos que se espera tras una desconexión antes de marcar abandono |
| `SERVER_PORT` | ❌ | `8083` | Puerto HTTP/WS |

---

## 7. Ejecución

### Desarrollo

```bash
JWT_SECRET="un-secreto-largo-compartido-con-gateway-y-userservice" \
INTERNAL_API_KEY="una-clave-interna" \
USER_SERVICE_URL="http://localhost:8081" \
CORS_ALLOWED_ORIGINS="http://localhost:5173" \
mvn spring-boot:run
```

```bash
curl http://localhost:8083/actuator/health
```

### Producción (JAR)

```bash
mvn -q -DskipTests package
java -jar target/gameservice-0.1.0.jar
```

### Docker / Docker Compose

```bash
docker build -t uno-game-service .
docker compose up -d --build game-service
```

> **Nota de despliegue:** en producción, este servicio debe ser alcanzable **directamente** para
> el WebSocket (no solo vía el Gateway) — ver el proxy `nginx` en [`deploy/nginx/edge.conf`](../deploy/nginx/edge.conf)
> que enruta `/ws-game` con las cabeceras `Upgrade`/`Connection` necesarias.

---

## 8. Estructura del proyecto

```
GameService/
├── src/main/java/com/unopower/gameservice/
│   ├── GameServiceApplication.java
│   ├── controller/
│   │   ├── ChatController.java            # STOMP: /app/salas/{codigo}/chat
│   │   ├── PartidaWebSocketController.java # STOMP: sincronizar/jugar/robar/decir-uno/powerups
│   │   ├── PartidaInternalController.java  # REST interno: POST /internal/partidas/{codigo}/iniciar
│   │   └── RosterController.java           # REST interno: POST /internal/salas/{codigo}/roster
│   ├── service/
│   │   ├── PartidaService.java     # orquestacion, broadcast, metricas KPI
│   │   ├── PartidaRegistry.java    # ConcurrentHashMap<codigo, Partida> en memoria
│   │   ├── MazoFactory.java        # construye y baraja el mazo de 108 cartas
│   │   ├── ReconexionService.java  # escucha SessionDisconnectEvent/SessionConnectedEvent
│   │   ├── UserServiceClient.java  # notifica resultado al terminar (X-Internal-Key)
│   │   └── JugadaInvalidaException.java
│   ├── model/
│   │   ├── Partida.java          # motor de reglas (aggregate root, "synchronized")
│   │   ├── Jugador.java          # mano, power-ups, flags (dijoUno, escudo, saltado, abandonado)
│   │   ├── Carta.java            # record inmutable (color, tipo)
│   │   ├── Color.java            # ROJO/AMARILLO/VERDE/AZUL/COMODIN
│   │   ├── TipoCarta.java        # NUMERO/SALTAR/REVERSA/MAS_DOS/COMODIN/COMODIN_MAS_CUATRO
│   │   ├── PowerUpTipo.java      # ROBO_FORZADO/ESPIA/SALTO_SELECTIVO/DUPLICADOR/ESCUDO
│   │   ├── PowerUpActivo.java    # power-up en el tablero, captura CAS con AtomicReference
│   │   └── EstadoPartida.java    # EN_CURSO/FINALIZADA
│   ├── dto/                      # 12 records: Estado, Mano, Jugador(Publico/Inicial), PowerUp,
│   │                              #  Espia, Jugar/Usar/CapturarRequest, Mensaje(Chat), SalaRoster, Error
│   ├── config/
│   │   ├── WebSocketConfig.java              # /ws-game, brokers /topic y /queue
│   │   ├── StompAuthChannelInterceptor.java  # valida JWT en el CONNECT
│   │   ├── UsuarioPrincipal.java             # Principal resuelto (userId + username)
│   │   ├── JwtProperties.java
│   │   └── InternalApiKeyFilter.java         # protege /internal/** (Servlet Filter)
│   └── ...
├── src/main/resources/
│   ├── application.yml
│   └── logback-spring.xml
├── src/test/java/…/ (5 archivos, 41 tests)
├── Dockerfile
└── pom.xml
```

---

## 9. Flujo interno

### Arranque de una partida

1. Lobby Service llama `POST /internal/partidas/{codigo}/iniciar` con la lista de jugadores.
2. `PartidaService.iniciarPartida` construye un `Partida` nuevo: baraja el mazo (108 cartas),
   reparte 7 a cada jugador, destapa la primera carta de descarte (si sale un `+4`, se devuelve
   al fondo del mazo y se repite — regla estándar de UNO).
3. Se guarda en `PartidaRegistry` (memoria) y se publica el primer `EstadoPartidaDto` +
   `ManoDto` de cada jugador por WebSocket.

### Una jugada (`jugar`)

1. El cliente publica a `/app/partidas/{codigo}/jugar` con la carta y, si aplica, el color elegido.
2. `Partida.jugarCarta` (método `synchronized`) valida: partida en curso, es tu turno, tienes esa
   carta en mano, es jugable sobre el descarte actual (mismo color/número/símbolo, o comodín).
3. Si es un comodín sin color elegido → `JugadaInvalidaException` → el cliente recibe el error
   por `/user/queue/errores` (no por el topic público).
4. Se aplica el efecto de la carta (saltar/reversa/+2/+4/comodín) y se avanza el turno.
5. Si la mano queda vacía, la partida termina inmediatamente (sin esperar el siguiente turno).
6. Se recalcula si toca generar un power-up (cada 3 turnos, ver [§12](#12-power-ups-hu-04)).
7. Se publica el estado nuevo a todos + la mano actualizada a cada jugador individualmente.
8. Si la partida terminó, se notifica el resultado a User Service (una sola vez, CAS).

### Sincronización (`sincronizar`)

El broker STOMP simple **no reenvía mensajes pasados** a un suscriptor nuevo. Un cliente que se
conecta tarde (o se reconecta tras perder la señal) no vería nada del estado actual hasta la
siguiente jugada de otro. Por eso el cliente **siempre** llama a
`/app/partidas/{codigo}/sincronizar` justo después del `CONNECT` — esto fuerza un re-broadcast
del estado actual solo para forzar que el suscriptor reciba el snapshot vigente.

---

## 10. API REST interna

Ambos endpoints son **servicio-a-servicio**, no pasan por el Gateway, protegidos con
`X-Internal-Key` (ver [§15](#15-seguridad)).

### `POST /internal/partidas/{codigo}/iniciar`

Llamado por Lobby Service.

**Body:**
```json
{
  "jugadores": [
    { "userId": "guest-...", "username": "Ana" },
    { "userId": "guest-...", "username": "Beto" }
  ]
}
```

**Respuesta:** `201 Created` (sin body; el estado se distribuye por WebSocket).

### `POST /internal/salas/{codigo}/roster`

Llamado por Lobby Service cada vez que cambia el roster del lobby (alguien se une). Retransmite
el roster por `/topic/sala/{codigo}/lobby` a los clientes ya conectados al WebSocket que están
esperando en la sala.

**Body:** mismo formato que `SalaResponse` de Lobby Service (`SalaRosterDto`).

**Respuesta:** `200 OK` (sin body).

---

## 11. WebSocket / STOMP

**Endpoint único:** `ws(s)://<host>/ws-game` (SockJS habilitado como *fallback*).

**Autenticación:** header STOMP `Authorization: Bearer <jwt>` en el frame `CONNECT`. Sin él, o si
es inválido/expirado, el `CONNECT` se rechaza (ver [§15](#15-seguridad)).

### Canales de suscripción (servidor → cliente)

| Destino | Cuándo se publica | Payload |
|---|---|---|
| `/topic/sala/{codigo}/lobby` | Cambia el roster del lobby (antes de iniciar) | `SalaRosterDto` |
| `/topic/sala/{codigo}/chat` | Alguien envía un mensaje de chat | `MensajeChatDto` |
| `/topic/sala/{codigo}/juego` | Cualquier cambio de estado de la partida (jugada, robo, power-up, fin) | `EstadoPartidaDto` (público — nunca incluye manos ajenas) |
| `/user/queue/mano` | Después de cada cambio de estado, a **cada** jugador individualmente | `ManoDto` (tu mano privada + tu inventario de power-ups) |
| `/user/queue/espia` | Solo a quien usó el power-up Espía | `EspiaResultadoDto` (la mano del objetivo) |
| `/user/queue/errores` | Jugada inválida o error inesperado | `ErrorDto { mensaje }` |

### Destinos de publicación (cliente → servidor)

Todos bajo el prefijo `/app` (configurado en `WebSocketConfig`).

| Destino | Payload | Efecto |
|---|---|---|
| `/app/salas/{codigo}/chat` | `{ "texto": "hola" }` | Difunde el mensaje a `/topic/sala/{codigo}/chat` (máx. 300 caracteres, se trunca; vacío se descarta) |
| `/app/partidas/{codigo}/sincronizar` | *(sin body)* | Fuerza re-broadcast del estado actual |
| `/app/partidas/{codigo}/jugar` | `{ "carta": {"color":"ROJO","tipo":"NUMERO", ...}, "colorElegido": "AZUL" }` | Intenta jugar esa carta (`colorElegido` solo para comodines) |
| `/app/partidas/{codigo}/robar` | *(sin body)* | Roba 1 carta y pasa el turno |
| `/app/partidas/{codigo}/decir-uno` | *(sin body)* | Marca `dijoUno = true` (se resetea automáticamente si vuelves a tener ≠1 carta) |
| `/app/partidas/{codigo}/powerups/capturar` | `{ "powerUpId": "..." }` | Intenta capturar el power-up visible en el tablero (primero en llegar gana) |
| `/app/partidas/{codigo}/powerups/usar` | `{ "tipo": "ROBO_FORZADO", "objetivoUserId": "..." }` | Usa un power-up ya capturado de tu inventario |

### Ejemplo de sesión completa (cliente STOMP.js)

```javascript
const client = new Client({
  webSocketFactory: () => new SockJS("https://api.example.com/ws-game"),
  connectHeaders: { Authorization: `Bearer ${token}` },
  onConnect: () => {
    client.subscribe(`/topic/sala/${codigo}/juego`, (msg) => actualizarEstado(JSON.parse(msg.body)));
    client.subscribe("/user/queue/mano", (msg) => actualizarMano(JSON.parse(msg.body)));
    client.subscribe("/user/queue/errores", (msg) => mostrarError(JSON.parse(msg.body)));
    client.publish({ destination: `/app/partidas/${codigo}/sincronizar` });
  },
});
client.activate();

// Jugar una carta numérica roja
client.publish({
  destination: `/app/partidas/${codigo}/jugar`,
  body: JSON.stringify({ carta: { color: "ROJO", tipo: "NUMERO", numero: 7 }, colorElegido: null }),
});
```

### `EstadoPartidaDto` — ejemplo completo

```json
{
  "codigo": "QTUQY2",
  "jugadores": [
    { "userId": "guest-a", "username": "Ana", "cantidadCartas": 6, "dijoUno": false, "escudoActivo": false, "abandonado": false },
    { "userId": "guest-b", "username": "Beto", "cantidadCartas": 1, "dijoUno": true, "escudoActivo": true, "abandonado": false }
  ],
  "turnoActualUserId": "guest-a",
  "cartaSuperior": { "color": "AZUL", "tipo": "MAS_DOS" },
  "colorActual": "AZUL",
  "direccion": 1,
  "estado": "EN_CURSO",
  "ganadorUserId": null,
  "powerUpActivo": { "id": "8f2c...", "tipo": "ESPIA" }
}
```

Nótese que `JugadorPublicoDto` **nunca** expone las cartas de otro jugador, solo su cantidad —
esa es la base de la detección de "robo" en el frontend (comparar `cantidadCartas` entre dos
snapshots consecutivos, ver [`FRONT/README.md`](../FRONT/README.md#13-animación-de-robo-de-cartas)).

---

## 12. Power-ups (HU-04)

Cada 3 turnos jugados (`TURNOS_PARA_POWERUP`), si no hay ya uno visible, aparece un power-up
aleatorio en el tablero (`PowerUpActivo`, visible a todos vía `powerUpActivo` en el estado).
**Cualquier** jugador puede intentar capturarlo publicando a `/powerups/capturar` — el primero en
llegar gana, resuelto con un `compare-and-set` atómico sobre `AtomicReference<String>` (no un
lock de la partida completa), para que la resolución de la carrera no dependa de en qué orden el
hilo de cada request logra entrar a un bloque `synchronized`.

| Power-up | Efecto | Requiere objetivo |
|---|---|---|
| **Robo forzado** | El objetivo roba 2 cartas (bloqueado si tiene escudo activo) | ✅ |
| **Espía** | Ves la mano completa del objetivo una sola vez (mensaje privado por `/user/queue/espia`) | ✅ |
| **Salto selectivo** | El objetivo pierde su próximo turno | ✅ |
| **Duplicador** | Tu próximo `+2`/`+4` jugado duplica la cantidad de cartas que roba el afectado | ❌ (se auto-aplica) |
| **Escudo** | Bloquea el próximo robo forzado que recibirías (por carta o por power-up) | ❌ (se auto-aplica) |

Un power-up capturado se guarda en el inventario del jugador (`ManoDto.powerUpsInventario`) hasta
que decide usarlo con `/powerups/usar` — no se activa automáticamente al capturarlo.

---

## 13. Reconexión (HU-08)

`ReconexionService` escucha los eventos de sesión STOMP:

- **`SessionDisconnectEvent`**: inicia un temporizador de `RECONEXION_TIMEOUT_SEGUNDOS` (60s por
  defecto) para ese `userId`. La partida sigue corriendo (el turno del jugador desconectado se
  puede saltar por las reglas normales si le toca y no juega, no hay lógica especial de "pausa").
- **`SessionConnectedEvent`**: si el mismo `userId` reconecta antes de que expire el timeout, se
  cancela el temporizador — no pasa nada más. El cliente reconectado llama a `sincronizar` para
  ponerse al día (ver [§9](#9-flujo-interno)).
- Si el timeout expira sin reconexión, `PartidaService.marcarAbandonado` marca al jugador como
  `abandonado` (visible en `JugadorPublicoDto.abandonado`), y si con eso quedan menos de 2
  jugadores activos, la partida termina automáticamente declarando ganador al único activo
  restante.

---

## 14. Modelo en memoria y concurrencia

- `PartidaRegistry` usa `ConcurrentHashMap<String, Partida>` — múltiples salas se procesan en
  paralelo sin contención entre ellas.
- **Dentro** de una misma `Partida`, los métodos que mutan estado (`jugarCarta`,
  `robarCartaPorTurno`, `decirUno`, `usarPowerUp`, `marcarAbandonado`) son `synchronized` sobre la
  instancia — dos jugadas simultáneas en la misma sala se serializan, nunca se corrompen mutuamente.
- La captura de power-up es la **única** operación que deliberadamente evita el lock de instancia
  completo (usa CAS sobre `AtomicReference` dentro de `PowerUpActivo`) — se hizo así para que
  "quién capturó primero" se resuelva por el CAS mismo, no por el orden de adquisición de un
  monitor compartido con las demás operaciones de juego.
- El fin de partida (por última carta o por abandono) usa un segundo CAS
  (`AtomicBoolean finNotificado`) para garantizar que la notificación a User Service y el
  incremento del contador `uno.partidas.completadas` ocurran **exactamente una vez**, sin importar
  cuál de los dos caminos disparó el fin.

---

## 15. Seguridad

- **JWT revalidado en el `CONNECT` STOMP** (`StompAuthChannelInterceptor`) — no delega esto al
  Gateway porque el WebSocket no pasa por él. Sin token válido, el `CONNECT` se rechaza con
  `MessagingException` antes de que se establezca cualquier suscripción.
- **Identidad autoritativa del servidor**: todas las acciones de juego usan `principal.getName()`
  (resuelto del JWT verificado), **nunca** un `userId` que el cliente pudiera incluir en el
  payload — así ni siquiera manipulando el JSON enviado se puede jugar en nombre de otro usuario.
- **`X-Internal-Key`** (`InternalApiKeyFilter`, comparación en tiempo constante) protege
  `/internal/partidas/**` e `/internal/salas/**` de llamadas externas directas.
- **CORS restringido** también en el handshake WebSocket (`setAllowedOriginPatterns`), no solo en
  el REST del Gateway.
- **Nunca se expone la mano de otro jugador** por el canal público — el único camino para ver
  cartas ajenas es el power-up Espía, y ese resultado se envía por cola privada
  (`/user/queue/espia`) solo a quien lo usó.

---

## 16. Logs

JSON estructurado, `service: "game-service"`. Ejemplo real:

```json
{
  "@timestamp": "2026-07-08T22:14:03.512Z",
  "message": "partida finalizada codigo=QTUQY2 ganador=guest-b",
  "logger_name": "com.unopower.gameservice.service.PartidaService",
  "level": "INFO",
  "service": "game-service"
}
```

Las jugadas inválidas (turno equivocado, carta no jugable) se loguean en `WARN` — son parte del
flujo esperado (anti-trampa), no errores del sistema. Solo las excepciones inesperadas
(`MessageExceptionHandler(Exception.class)`) se loguean en `ERROR` y cuentan para el KPI-4.

---

## 17. Observabilidad y KPIs (HU-11)

`GET /actuator/prometheus` expone, además de las métricas técnicas estándar (JVM, HTTP, etc.),
**4 métricas de negocio** propias de este servicio:

| Métrica | Tipo | Fórmula / significado |
|---|---|---|
| `uno.partidas.completadas` (**KPI-1**) | Counter | Se incrementa una vez por partida al finalizar (por victoria o por abandono masivo) |
| `uno.jugada.broadcast` (**KPI-2**) | Timer/histograma | Latencia entre recibir una jugada válida y terminar de publicar el estado actualizado — mide la percepción de "instantaneidad" del juego |
| `uno.partidas.activas` (**KPI-3**) | Gauge | `PartidaRegistry::cantidadActivas` en tiempo real |
| `uno.partidas.errores` (**KPI-4**) | Counter | Errores **inesperados** (no incluye jugadas inválidas, que son parte del flujo normal de validación) |

Visualizadas en el dashboard `kpis-negocio.json` de Grafana — ver
[`ObservabilityStack/README.md`](../ObservabilityStack/README.md).

---

## 18. Testing

```bash
mvn test
mvn test jacoco:report   # target/site/jacoco/index.html (95% de cobertura en model/)
```

**41 tests** (JUnit 5 + Mockito) en 5 archivos:

| Archivo | Cubre |
|---|---|
| `PartidaTest` | Reglas completas del motor: jugadas válidas/inválidas, efectos de saltar/reversa/+2/+4, comodín exige color, fin de partida al vaciar mano, robo cuando el mazo se agota (reciclaje del descarte), abandono con recuento de activos |
| `MazoFactoryTest` | El mazo generado tiene exactamente 108 cartas con la distribución estándar de UNO |
| `PartidaServiceTest` | Orquestación: broadcast de estado y mano, métricas incrementadas correctamente, notificación a User Service exactamente una vez |
| `StompAuthChannelInterceptorTest` | Rechaza `CONNECT` sin token, con token inválido/expirado; acepta y setea el `Principal` con token válido |
| `ReconexionServiceTest` | Cancela el timeout si reconecta a tiempo; marca abandonado si expira; termina la partida si quedan <2 activos |

---

## 19. Despliegue

- **Docker**: `Dockerfile` multi-stage (Maven → JRE Alpine).
- **Docker Compose**: servicio `game-service` en [`docker-compose.yml`](../docker-compose.yml) y
  en [`deploy/docker-compose.prod.yml`](../deploy/docker-compose.prod.yml).
- **AWS**: el `nginx` de borde (`deploy/nginx/edge.conf`) enruta tanto `/api/**` (hacia el
  Gateway) como `/ws-game` (directo a este servicio, con `proxy_set_header Upgrade $http_upgrade`)
  bajo el mismo dominio HTTPS — necesario porque CloudFront (HTTPS) bloquearía un WebSocket en
  texto plano por contenido mixto. Ver [`deploy/DEPLOY.md`](../deploy/DEPLOY.md).

---

## 20. Solución de problemas

| Síntoma | Causa probable | Solución |
|---|---|---|
| El `CONNECT` STOMP falla inmediatamente | JWT ausente, expirado, o `JWT_SECRET` distinto al del Gateway/User Service | Verificar que las 3 variables `JWT_SECRET` sean idénticas en los 3 servicios |
| La partida se queda en "Conectando…" indefinidamente | El cliente no llamó a `sincronizar` tras el `CONNECT` (el broker simple no reenvía mensajes pasados) | Confirmar que el frontend publica a `/app/partidas/{codigo}/sincronizar` en el `onConnect` |
| Un jugador desaparece del juego sin aviso | Se agotó su ventana de reconexión (`RECONEXION_TIMEOUT_SEGUNDOS`) | Comportamiento esperado (HU-08); revisar `abandonado` en `JugadorPublicoDto` |
| Las estadísticas no se actualizan al ganar | `USER_SERVICE_URL` incorrecta o User Service caído | Revisar logs en `WARN`; el fin de partida no se bloquea por esto, pero las stats no llegan |
| Mixed-content error en el navegador al conectar el WS en producción | El frontend (HTTPS vía CloudFront) intenta un WS en `ws://` en vez de `wss://` | Usar el dominio HTTPS único con el proxy nginx de borde, no la IP pública directa del backend |

---

## 21. Roadmap

- Persistir partidas en curso (ej. Redis) para sobrevivir a un reinicio del proceso sin perder el
  estado — actualmente fuera de alcance, documentado como deuda técnica aceptada en el Inception doc.
- Historial de partidas jugadas (actualmente solo se guarda el contador agregado en User Service).
- Modo espectador (ver una partida en curso sin participar).
- Más tipos de power-up.

---

## 22. Contribución

1. Toda mutación del estado de una `Partida` debe pasar por un método `synchronized` de esa
   clase — nunca mutar sus colecciones desde fuera.
2. Tests obligatorios en `PartidaTest` para cualquier cambio de regla de juego.
3. `mvn test` antes de abrir PR.
4. Commits en [Conventional Commits](https://www.conventionalcommits.org/).

---

## 23. Licencia

MIT — ver [`LICENSE`](../LICENSE) en el repositorio raíz.

---

## 24. Autor

**DUCU** — Desarrollador Full-Stack (individual), proyecto de la asignatura ARSW.
Organización GitHub: [UNO-POWER](https://github.com/UNO-POWER).

Repositorio raíz del sistema: **[UNO Power-Ups Arena](../README.md)**.
