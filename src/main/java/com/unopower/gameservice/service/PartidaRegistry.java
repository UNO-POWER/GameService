package com.unopower.gameservice.service;

import com.unopower.gameservice.model.EstadoPartida;
import com.unopower.gameservice.model.Partida;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de partidas en memoria del Game Service (ver seccion 12.a del Inception doc):
 * ConcurrentHashMap&lt;partidaId, Partida&gt;. El snapshot periodico a RDS queda como
 * evolucion opcional segun la misma seccion ("Snapshot opcional a RDS").
 */
@Component
public class PartidaRegistry {

    private final ConcurrentHashMap<String, Partida> partidas = new ConcurrentHashMap<>();

    public void guardar(Partida partida) {
        partidas.put(partida.getCodigo(), partida);
    }

    public Partida obtener(String codigo) {
        Partida partida = partidas.get(codigo);
        if (partida == null) {
            throw new JugadaInvalidaException("no hay una partida activa para la sala " + codigo);
        }
        return partida;
    }

    public boolean existe(String codigo) {
        return partidas.containsKey(codigo);
    }

    public void eliminar(String codigo) {
        partidas.remove(codigo);
    }

    /** KPI-3 (HU-11): salas con partida en curso, expuesto como gauge de Micrometer. */
    public int cantidadActivas() {
        return (int) partidas.values().stream()
                .filter(p -> p.getEstado() == EstadoPartida.EN_CURSO)
                .count();
    }

    /**
     * HU-08: al detectar una desconexion WebSocket solo se conoce el userId (del Principal),
     * no la sala; esta busqueda resuelve en que partida activa estaba jugando.
     */
    public Optional<Partida> buscarPartidaActivaDe(String userId) {
        return partidas.values().stream()
                .filter(p -> p.getEstado() == EstadoPartida.EN_CURSO)
                .filter(p -> p.getJugadores().stream()
                        .anyMatch(j -> j.getUserId().equals(userId) && !j.isAbandonado()))
                .findFirst();
    }
}
