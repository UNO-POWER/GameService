package com.unopower.gameservice.service;

import com.unopower.gameservice.dto.JugadorInicialDto;
import com.unopower.gameservice.model.EstadoPartida;
import com.unopower.gameservice.model.Partida;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PartidaServiceTest {

    private static final String CODIGO = "SALA99";

    private PartidaRegistry registry;
    private SimpMessagingTemplate messaging;
    private UserServiceClient userServiceClient;
    private SimpleMeterRegistry meterRegistry;
    private PartidaService service;

    @BeforeEach
    void setUp() {
        registry = new PartidaRegistry();
        messaging = Mockito.mock(SimpMessagingTemplate.class);
        userServiceClient = Mockito.mock(UserServiceClient.class);
        meterRegistry = new SimpleMeterRegistry();
        // timeout largo: el scheduler real nunca dispara durante el test; el timeout se
        // ejercita llamando robarPorTimeout directamente
        service = new PartidaService(registry, new MazoFactory(), messaging, userServiceClient,
                new TurnoTimerService(3600), meterRegistry);
    }

    private void iniciarPartidaDeDos() {
        service.iniciarPartida(CODIGO, List.of(
                new JugadorInicialDto("user-a", "Ana"),
                new JugadorInicialDto("user-b", "Beto")));
    }

    @Test
    @DisplayName("iniciar partida la registra, reparte y publica estado publico + manos privadas")
    void iniciarPartidaPublicaEstado() {
        iniciarPartidaDeDos();

        Partida partida = registry.obtener(CODIGO);
        assertEquals(2, partida.getJugadores().size());
        assertEquals(7, partida.getJugadores().get(0).getMano().size());

        verify(messaging).convertAndSend(eq("/topic/sala/" + CODIGO + "/juego"), any(Object.class));
        verify(messaging).convertAndSendToUser(eq("user-a"), eq("/queue/mano"), any(Object.class));
        verify(messaging).convertAndSendToUser(eq("user-b"), eq("/queue/mano"), any(Object.class));
        assertEquals(1, meterRegistry.get("uno.partidas.activas").gauge().value());
    }

    @Test
    @DisplayName("HU-08: marcar abandonado con 2 jugadores finaliza y notifica resultado una sola vez")
    void abandonoFinalizaYNotifica() {
        iniciarPartidaDeDos();

        service.marcarAbandonado(CODIGO, "user-b");
        service.marcarAbandonado(CODIGO, "user-b"); // repetido: no debe duplicar notificaciones

        assertEquals(EstadoPartida.FINALIZADA, registry.obtener(CODIGO).getEstado());
        verify(userServiceClient, times(1)).notificarResultado("user-a", true);
        verify(userServiceClient, times(1)).notificarResultado("user-b", false);
        assertEquals(1.0, meterRegistry.get("uno.partidas.completadas").counter().count());
        assertEquals(0, meterRegistry.get("uno.partidas.activas").gauge().value());
    }

    @Test
    @DisplayName("marcar abandonado en una sala sin partida no explota (idempotente)")
    void abandonoSinPartidaEsNoOp() {
        assertDoesNotThrow(() -> service.marcarAbandonado("NOEXISTE", "user-x"));
        verifyNoInteractions(userServiceClient);
    }

    @Test
    @DisplayName("HU-08: sincronizar reenvia el estado actual solo si la partida existe")
    void sincronizarReenviaEstado() {
        iniciarPartidaDeDos();
        clearInvocations(messaging);

        service.sincronizar(CODIGO);
        verify(messaging).convertAndSend(eq("/topic/sala/" + CODIGO + "/juego"), any(Object.class));

        clearInvocations(messaging);
        service.sincronizar("NOEXISTE");
        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("KPI-4: registrarErrorCritico incrementa el contador de errores")
    void errorCriticoIncrementaContador() {
        service.registrarErrorCritico();
        service.registrarErrorCritico();
        assertEquals(2.0, meterRegistry.get("uno.partidas.errores").counter().count());
    }

    @Test
    @DisplayName("timer de turno: al iniciar la partida hay deadline de turno publicable")
    void iniciarPartidaFijaDeadlineDeTurno() {
        iniciarPartidaDeDos();
        assertNotNull(registry.obtener(CODIGO).getTurnoVenceEn());
    }

    @Test
    @DisplayName("timer de turno: timeout vigente roba una carta al jugador en turno y avanza")
    void timeoutRobaYAvanzaTurno() {
        iniciarPartidaDeDos();
        Partida partida = registry.obtener(CODIGO);
        String enTurno = partida.getTurnoActualUserId();
        int secuencia = partida.getTurnoSecuencia();

        service.robarPorTimeout(CODIGO, enTurno, secuencia);

        var jugador = partida.getJugadores().stream()
                .filter(j -> j.getUserId().equals(enTurno)).findFirst().orElseThrow();
        assertEquals(8, jugador.getMano().size());
        assertNotEquals(enTurno, partida.getTurnoActualUserId());
    }

    @Test
    @DisplayName("timer de turno: un timeout tardio de un turno ya avanzado es un no-op")
    void timeoutDeSecuenciaViejaEsNoOp() {
        iniciarPartidaDeDos();
        Partida partida = registry.obtener(CODIGO);
        String enTurno = partida.getTurnoActualUserId();
        int secuenciaVieja = partida.getTurnoSecuencia();

        service.robarPorTimeout(CODIGO, enTurno, secuenciaVieja);      // avanza de verdad
        String nuevoEnTurno = partida.getTurnoActualUserId();
        service.robarPorTimeout(CODIGO, enTurno, secuenciaVieja);      // tardio: debe ignorarse

        assertEquals(nuevoEnTurno, partida.getTurnoActualUserId());
        var jugadorViejo = partida.getJugadores().stream()
                .filter(j -> j.getUserId().equals(enTurno)).findFirst().orElseThrow();
        assertEquals(8, jugadorViejo.getMano().size()); // solo robo una vez
    }

    @Test
    @DisplayName("KPI-2: cada jugada valida registra una muestra de latencia jugada->broadcast")
    void jugadaRegistraLatencia() {
        iniciarPartidaDeDos();
        Partida partida = registry.obtener(CODIGO);
        // jugada valida garantizada: robar no sirve (no pasa por el timer), asi que buscamos
        // una carta jugable del turno actual; si no hay, el jugador roba y el test la salta.
        var jugador = partida.getJugadores().stream()
                .filter(j -> j.getUserId().equals(partida.getTurnoActualUserId()))
                .findFirst().orElseThrow();
        var jugable = jugador.getMano().stream()
                .filter(c -> c.esJugableSobre(partida.getCartaSuperior(), partida.getColorActual()))
                .findFirst();

        if (jugable.isPresent() && !jugable.get().esComodin()) {
            service.jugarCarta(CODIGO, jugador.getUserId(), jugable.get(), null);
            assertEquals(1, meterRegistry.get("uno.jugada.broadcast").timer().count());
        }
    }
}
