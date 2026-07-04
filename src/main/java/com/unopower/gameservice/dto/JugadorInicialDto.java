package com.unopower.gameservice.dto;

/** Roster recibido de Lobby Service al iniciar partida (espejo de su MiembroResponse). */
public record JugadorInicialDto(String userId, String username) {
}
