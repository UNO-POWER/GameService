package com.unopower.gameservice.service;

import com.unopower.gameservice.model.Partida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import jakarta.annotation.PreDestroy;
import java.security.Principal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * HU-08 (reconexion tras desconexion). El cliente STOMP reintenta solo y pide un resync
 * al reconectar (accion "sincronizar"), asi que del lado servidor lo unico que falta es
 * el escenario de timeout del Gherkin: si un jugador permanece desconectado mas de
 * {@code game.reconexion.timeout-segundos} (60s por defecto), se le marca como abandonado
 * y la partida continua con los restantes. Si reconecta dentro de la ventana, el timer se
 * cancela y no pierde su lugar (ni su turno: el turno no avanza por desconexion).
 */
@Service
public class ReconexionService {

    private static final Logger log = LoggerFactory.getLogger(ReconexionService.class);

    private final PartidaRegistry registry;
    private final PartidaService partidaService;
    private final long timeoutSegundos;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reconexion-timeout");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeoutsPendientes = new ConcurrentHashMap<>();

    public ReconexionService(PartidaRegistry registry, PartidaService partidaService,
                              @Value("${game.reconexion.timeout-segundos:60}") long timeoutSegundos) {
        this.registry = registry;
        this.partidaService = partidaService;
        this.timeoutSegundos = timeoutSegundos;
    }

    @EventListener
    public void onDesconexion(SessionDisconnectEvent event) {
        Principal user = event.getUser();
        if (user != null) {
            programarTimeoutAbandono(user.getName());
        }
    }

    @EventListener
    public void onConexion(SessionConnectedEvent event) {
        Principal user = event.getUser();
        if (user != null) {
            cancelarTimeout(user.getName());
        }
    }

    void programarTimeoutAbandono(String userId) {
        Optional<Partida> partida = registry.buscarPartidaActivaDe(userId);
        if (partida.isEmpty()) {
            return; // no estaba jugando: nada que abandonar
        }
        String codigo = partida.get().getCodigo();
        log.info("jugador desconectado userId={} codigo={}, timeout de abandono en {}s", userId, codigo, timeoutSegundos);

        ScheduledFuture<?> anterior = timeoutsPendientes.put(userId, scheduler.schedule(() -> {
            timeoutsPendientes.remove(userId);
            log.info("timeout de reconexion vencido userId={} codigo={}, marcando abandonado", userId, codigo);
            partidaService.marcarAbandonado(codigo, userId);
        }, timeoutSegundos, TimeUnit.SECONDS));
        if (anterior != null) {
            anterior.cancel(false);
        }
    }

    void cancelarTimeout(String userId) {
        ScheduledFuture<?> pendiente = timeoutsPendientes.remove(userId);
        if (pendiente != null) {
            pendiente.cancel(false);
            log.info("jugador reconectado userId={}, timeout de abandono cancelado", userId);
        }
    }

    @PreDestroy
    void apagar() {
        scheduler.shutdownNow();
    }
}
