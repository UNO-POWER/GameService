package com.unopower.gameservice.service;

import com.unopower.gameservice.model.Partida;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Timer de turno: cada jugador tiene {@code game.turno.timeout-segundos} (20s por defecto)
 * para actuar; si no lo hace, PartidaService roba una carta en su nombre y pasa el turno
 * (mismo efecto que el boton "Robar": penaliza sin tomar decisiones estrategicas por el
 * jugador). La secuencia de turno de la Partida evita dos carreras: un "sincronizar" no
 * resetea el timer del turno vigente, y un timeout viejo que dispara tarde no roba en un
 * turno que ya avanzo.
 */
@Service
public class TurnoTimerService {

    @FunctionalInterface
    public interface TimeoutHandler {
        void alExpirarTurno(String codigo, String userId, int secuencia);
    }

    private record TimerActivo(int secuencia, ScheduledFuture<?> future) {
    }

    private final long timeoutSegundos;
    private final ConcurrentHashMap<String, TimerActivo> timers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "turno-timeout");
        t.setDaemon(true);
        return t;
    });

    public TurnoTimerService(@Value("${game.turno.timeout-segundos:20}") long timeoutSegundos) {
        this.timeoutSegundos = timeoutSegundos;
    }

    /**
     * Programa el timeout del turno vigente si aun no existe. Idempotente por secuencia:
     * re-publicaciones del mismo turno (p.ej. "sincronizar") no reinician la cuenta.
     * Tambien fija {@code turnoVenceEn} en la partida para que el frontend muestre el countdown.
     */
    public void asegurar(Partida partida, TimeoutHandler handler) {
        String codigo = partida.getCodigo();
        int secuencia = partida.getTurnoSecuencia();

        TimerActivo actual = timers.get(codigo);
        if (actual != null && actual.secuencia() == secuencia) {
            return; // mismo turno: el timer sigue corriendo, no se resetea
        }
        if (actual != null) {
            actual.future().cancel(false);
        }

        partida.setTurnoVenceEn(Instant.now().plusSeconds(timeoutSegundos));
        String userIdEnTurno = partida.getTurnoActualUserId();
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            timers.remove(codigo);
            handler.alExpirarTurno(codigo, userIdEnTurno, secuencia);
        }, timeoutSegundos, TimeUnit.SECONDS);
        timers.put(codigo, new TimerActivo(secuencia, future));
    }

    public void cancelar(String codigo) {
        TimerActivo activo = timers.remove(codigo);
        if (activo != null) {
            activo.future().cancel(false);
        }
    }

    long getTimeoutSegundos() {
        return timeoutSegundos;
    }

    @PreDestroy
    void apagar() {
        scheduler.shutdownNow();
    }
}
