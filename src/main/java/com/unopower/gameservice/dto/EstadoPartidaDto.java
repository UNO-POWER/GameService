package com.unopower.gameservice.dto;

import com.unopower.gameservice.model.Carta;
import com.unopower.gameservice.model.Color;
import com.unopower.gameservice.model.Partida;

import java.time.Instant;
import java.util.List;

public record EstadoPartidaDto(
        String codigo,
        List<JugadorPublicoDto> jugadores,
        String turnoActualUserId,
        Carta cartaSuperior,
        Color colorActual,
        int direccion,
        String estado,
        String ganadorUserId,
        PowerUpPublicoDto powerUpActivo,
        /** Instante en que vence el turno actual (countdown en el frontend); null si finalizo. */
        Instant turnoVenceEn
) {
    public static EstadoPartidaDto from(Partida partida) {
        List<JugadorPublicoDto> jugadores = partida.getJugadores().stream()
                .map(JugadorPublicoDto::from)
                .toList();
        var powerUp = partida.getPowerUpActivo();
        return new EstadoPartidaDto(
                partida.getCodigo(),
                jugadores,
                partida.getEstado() == com.unopower.gameservice.model.EstadoPartida.EN_CURSO
                        ? partida.getTurnoActualUserId() : null,
                partida.getCartaSuperior(),
                partida.getColorActual(),
                partida.getDireccion(),
                partida.getEstado().name(),
                partida.getGanadorUserId(),
                powerUp != null ? new PowerUpPublicoDto(powerUp.getId(), powerUp.getTipo().name()) : null,
                partida.getEstado() == com.unopower.gameservice.model.EstadoPartida.EN_CURSO
                        ? partida.getTurnoVenceEn() : null
        );
    }
}
