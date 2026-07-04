package com.unopower.gameservice.dto;

import com.unopower.gameservice.model.Jugador;

/** Vista publica de un jugador: nunca expone su mano, solo cuantas cartas tiene. */
public record JugadorPublicoDto(
        String userId,
        String username,
        int cantidadCartas,
        boolean dijoUno,
        boolean escudoActivo,
        boolean abandonado
) {
    public static JugadorPublicoDto from(Jugador jugador) {
        return new JugadorPublicoDto(
                jugador.getUserId(),
                jugador.getUsername(),
                jugador.getMano().size(),
                jugador.isDijoUno(),
                jugador.isEscudoActivo(),
                jugador.isAbandonado()
        );
    }
}
