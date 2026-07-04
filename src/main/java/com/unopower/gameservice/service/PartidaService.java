package com.unopower.gameservice.service;

import com.unopower.gameservice.dto.EstadoPartidaDto;
import com.unopower.gameservice.dto.JugadorInicialDto;
import com.unopower.gameservice.dto.ManoDto;
import com.unopower.gameservice.model.Carta;
import com.unopower.gameservice.model.Color;
import com.unopower.gameservice.model.EstadoPartida;
import com.unopower.gameservice.model.Jugador;
import com.unopower.gameservice.model.Partida;
import com.unopower.gameservice.model.PowerUpTipo;
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

    public PartidaService(PartidaRegistry registry, MazoFactory mazoFactory,
                           SimpMessagingTemplate messagingTemplate, UserServiceClient userServiceClient) {
        this.registry = registry;
        this.mazoFactory = mazoFactory;
        this.messagingTemplate = messagingTemplate;
        this.userServiceClient = userServiceClient;
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
        Partida partida = registry.obtener(codigo);
        partida.jugarCarta(userId, carta, colorElegido);
        if (partida.getEstado() == EstadoPartida.EN_CURSO) {
            partida.generarPowerUpSiCorresponde();
        }
        publicarEstadoYManos(partida);
        notificarSiFinalizada(partida);
    }

    /**
     * El broker simple de STOMP no reenvia mensajes pasados a nuevos suscriptores: un
     * cliente que conecta despues de que la partida ya inicio (o se reconecta) no veria
     * nada hasta la siguiente jugada. Este mensaje fuerza un reenvio del estado actual.
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
                    new com.unopower.gameservice.dto.EspiaResultadoDto(objetivoUserId, objetivo.getUsername(), resultadoEspia.get()));
        }
        publicarEstadoYManos(partida);
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
        if (partida.getEstado() != EstadoPartida.FINALIZADA) {
            return;
        }
        log.info("partida finalizada codigo={} ganador={}", partida.getCodigo(), partida.getGanadorUserId());
        for (Jugador jugador : partida.getJugadores()) {
            boolean gano = jugador.getUserId().equals(partida.getGanadorUserId());
            userServiceClient.notificarResultado(jugador.getUserId(), gano);
        }
    }
}
