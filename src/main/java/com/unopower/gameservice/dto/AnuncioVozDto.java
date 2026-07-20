package com.unopower.gameservice.dto;

/** Anuncio de entrada/salida del canal de voz de la sala ("presente" | "salida"). */
public record AnuncioVozDto(String userId, String username, String tipo) {
}
