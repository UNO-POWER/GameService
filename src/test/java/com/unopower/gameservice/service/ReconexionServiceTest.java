package com.unopower.gameservice.service;

import com.unopower.gameservice.model.Carta;
import com.unopower.gameservice.model.Color;
import com.unopower.gameservice.model.Jugador;
import com.unopower.gameservice.model.Partida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * HU-08, escenario Gherkin "Timeout de reconexion": desconectado por mas del timeout se
 * marca abandonado; si reconecta antes, el timer se cancela.
 */
class ReconexionServiceTest {

    private static final String CODIGO = "SALA08";

    private PartidaRegistry registryConPartidaDe(String userIdA, String userIdB) {
        PartidaRegistry registry = new PartidaRegistry();
        Deque<Carta> mazo = new ArrayDeque<>();
        for (int i = 0; i < 14; i++) {
            mazo.addLast(Carta.numero(Color.VERDE, (i % 9) + 1));
        }
        mazo.addLast(Carta.numero(Color.ROJO, 5));
        registry.guardar(new Partida(CODIGO,
                List.of(new Jugador(userIdA, "Ana"), new Jugador(userIdB, "Beto")), mazo));
        return registry;
    }

    @Test
    @DisplayName("vencido el timeout, el jugador desconectado se marca como abandonado")
    void timeoutMarcaAbandonado() throws InterruptedException {
        PartidaRegistry registry = registryConPartidaDe("user-a", "user-b");
        PartidaService partidaService = Mockito.mock(PartidaService.class);
        ReconexionService reconexion = new ReconexionService(registry, partidaService, 0); // timeout inmediato

        reconexion.programarTimeoutAbandono("user-b");
        Thread.sleep(300);

        verify(partidaService, timeout(1000)).marcarAbandonado(CODIGO, "user-b");
    }

    @Test
    @DisplayName("si el jugador reconecta antes del timeout, no se le marca abandonado")
    void reconexionCancelaTimeout() throws InterruptedException {
        PartidaRegistry registry = registryConPartidaDe("user-a", "user-b");
        PartidaService partidaService = Mockito.mock(PartidaService.class);
        ReconexionService reconexion = new ReconexionService(registry, partidaService, 2);

        reconexion.programarTimeoutAbandono("user-b");
        reconexion.cancelarTimeout("user-b"); // simulacion de SessionConnectedEvent
        Thread.sleep(400);

        verify(partidaService, never()).marcarAbandonado(anyString(), anyString());
    }

    @Test
    @DisplayName("desconexion de alguien sin partida activa no programa nada")
    void desconexionSinPartidaEsNoOp() throws InterruptedException {
        PartidaRegistry registry = registryConPartidaDe("user-a", "user-b");
        PartidaService partidaService = Mockito.mock(PartidaService.class);
        ReconexionService reconexion = new ReconexionService(registry, partidaService, 0);

        reconexion.programarTimeoutAbandono("user-espectador");
        Thread.sleep(200);

        verify(partidaService, never()).marcarAbandonado(anyString(), anyString());
    }
}
