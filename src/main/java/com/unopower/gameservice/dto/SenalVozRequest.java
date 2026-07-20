package com.unopower.gameservice.dto;

/**
 * Senal WebRTC dirigida a un jugador concreto (offer/answer/ice/presente).
 * "datos" es el SDP o candidato ICE serializado: el servidor NUNCA lo interpreta,
 * solo lo retransmite (el audio viaja P2P entre navegadores, no por este servicio).
 */
public record SenalVozRequest(String objetivoUserId, String tipo, String datos) {
}
