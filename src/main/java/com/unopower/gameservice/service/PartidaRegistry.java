package com.unopower.gameservice.service;

import com.unopower.gameservice.model.Partida;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de partidas en memoria del Game Service (ver seccion 12.a del Inception doc):
 * ConcurrentHashMap&lt;partidaId, Partida&gt;. Sin persistencia todavia (snapshot a RDS
 * queda para cuando se implemente HU-08 completo).
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
}
