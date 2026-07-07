package com.unopower.gameservice.service;

import com.unopower.gameservice.dto.EspiaResultadoDto;
import com.unopower.gameservice.dto.EstadoPartidaDto;
import com.unopower.gameservice.dto.JugadorInicialDto;
import com.unopower.gameservice.dto.ManoDto;
import com.unopower.gameservice.model.Carta;
import com.unopower.gameservice.model.Color;
import com.unopower.gameservice.model.EstadoPartida;
import com.unopower.gameservice.model.Jugador;
import com.unopower.gameservice.model.Partida;
import com.unopower.gameservice.model.PowerUpTipo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PartidaService {

    private static final Logger log = LoggerFactory.getLogger(PartidaService.class);

    private final PartidaRegistry registry;
    private final MazoFactory mazoFactory;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserServiceClient userServiceClient;

    // KPIs de negocio (HU-11): valores actuales visibles en /actuator/prometheus y Grafana.
    private final Counter partidasCompletadas;   // KPI-1: partidas completadas por dia
    private final Timer latenciaJugadaBroadcast; // KPI-2: latencia p95 jugada -> broadcast
    private final Counter erroresCriticos;       // KPI-4: tasa de partidas sin errores criticos

    public PartidaService(PartidaRegistry registry, MazoFactory mazoFactory,
                           SimpMessagingTemplate messagingTemplate, UserServiceClient userServiceClient,
                           MeterRegistry meterRegistry) {
        this.registry = registry;
        this.mazoFactory = mazoFactory;
        this.messagingTemplate = messagingTemplate;
        this.userServiceClient = userServiceClient;
        this.partidasCompletadas = Counter.builder("uno.partidas.completadas")
                .description("KPI-1: partidas de UNO completadas (ganador o abandono masivo)")
                .register(meterRegistry);
        this.latenciaJugadaBroadcast = Timer.builder("uno.jugada.broadcast")
                .description("KPI-2: latencia entre jugada recibida y broadcast del estado")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.erroresCriticos = Counter.builder("uno.partidas.errores")
                .description("KPI-4: errores criticos inesperados durante partidas (no incluye jugadas invalidas)")
                .register(meterRegistry);
        meterRegistry.gauge("uno.partidas.activas", registry, PartidaRegistry::cantidadActivas);
    }

    public void iniciarPartida(String codigo, List<JugadorInicialDto> jugadoresIniciales) {
        List<Jugador> jugadores = jugadoresIniciales.stream()
                .map(j -> new Jugador(j.userId(), j.username()))
                .toList();
        Partida partida = new Partida(codigo, jugadores, mazoFactory.crearMazoBarajado());
        registry.guardar(partida);
        log.info("partida iniciada codigo={} jugadores={}", codigo, jugadores.size());
        publicarEstadoYManos(partida);
    }

    public void jugarCarta(String codigo, String userId, Carta carta, Color colorElegido) {
        Timer.Sample muestra = Timer.start();
        Partida partida = registry.obtener(codigo);
        partida.jugarCarta(userId, carta, colorElegido);
        if (partida.getEstado() == EstadoPartida.EN_CURSO) {
            partida.generarPowerUpSiCorresponde();
        }
        publicarEstadoYManos(partida);
        muestra.stop(latenciaJugadaBroadcast);
        notificarSiFinalizada(partida);
    }

    /**
     * El broker simple de STOMP no reenvia mensajes pasados a nuevos suscriptores: un
     * cliente que conecta despues de que la partida ya inicio (o se reconecta, HU-08)
     * no veria nada hasta la siguiente jugada. Este mensaje fuerza un reenvio del estado.
     */
    public void sincronizar(String codigo) {
        if (registry.existe(codigo)) {
            publicarEstadoYManos(registry.obtener(codigo));
        }
    }

    public void robar(String codigo, String userId) {
        Partida partida = registry.obtener(codigo);
        partida.robarCartaPorTurno(userId);
        publicarEstadoYManos(partida);
    }

    public void decirUno(String codigo, String userId) {
        Partida partida = registry.obtener(codigo);
        partida.decirUno(userId);
        publicarEstadoYManos(partida);
    }

    public void capturarPowerUp(String codigo, String userId, String powerUpId) {
        Partida partida = registry.obtener(codigo);
        boolean gano = partida.capturarPowerUp(userId, powerUpId);
        if (gano) {
            log.info("power-up capturado codigo={} userId={} powerUpId={}", codigo, userId, powerUpId);
            publicarEstadoYManos(partida);
        }
    }

    public void usarPowerUp(String codigo, String userId, PowerUpTipo tipo, String objetivoUserId) {
        Partida partida = registry.obtener(codigo);
        Optional<List<Carta>> resultadoEspia = partida.usarPowerUp(userId, tipo, objetivoUserId);
        if (resultadoEspia.isPresent()) {
            Jugador objetivo = partida.obtenerJugador(objetivoUserId);
            messagingTemplate.convertAndSendToUser(userId, "/queue/espia",
                    new EspiaResultadoDto(objetivoUserId, objetivo.getUsername(), resultadoEspia.get()));
        }
        publicarEstadoYManos(partida);
    }

    /**
     * HU-08 (timeout de reconexion): marca al jugador como abandonado, avisa a los demas
     * y, si la partida termina por quedarse sin rivales, dispara el flujo de fin de partida.
     */
    public void marcarAbandonado(String codigo, String userId) {
        if (!registry.existe(codigo)) {
            return;
        }
        Partida partida = registry.obtener(codigo);
        partida.marcarAbandonado(userId);
        publicarEstadoYManos(partida);
        notificarSiFinalizada(partida);
    }

    /** KPI-4: errores inesperados (no jugadas invalidas) reportados por el controller. */
    public void registrarErrorCritico() {
        erroresCriticos.increment();
    }

    private void publicarEstadoYManos(Partida partida) {
        EstadoPartidaDto estado = EstadoPartidaDto.from(partida);
        messagingTemplate.convertAndSend("/topic/sala/" + partida.getCodigo() + "/juego", estado);

        for (Jugador jugador : partida.getJugadores()) {
            ManoDto mano = new ManoDto(partida.getCodigo(), List.copyOf(jugador.getMano()),
                    List.copyOf(jugador.getPowerUpsInventario()));
            messagingTemplate.convertAndSendToUser(jugador.getUserId(), "/queue/mano", mano);
        }
    }

    private void notificarSiFinalizada(Partida partida) {
        if (partida.getEstado() != EstadoPartida.FINALIZADA || !partida.marcarFinNotificado()) {
            return;
        }
        partidasCompletadas.increment();
        log.info("partida finalizada codigo={} ganador={}", partida.getCodigo(), partida.getGanadorUserId());
        for (Jugador jugador : partida.getJugadores()) {
            boolean gano = jugador.getUserId().equals(partida.getGanadorUserId());
            userServiceClient.notificarResultado(jugador.getUserId(), gano);
        }
    }
}
