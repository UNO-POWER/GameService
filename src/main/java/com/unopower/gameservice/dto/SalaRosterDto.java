package com.unopower.gameservice.dto;

import java.util.List;

/**
 * Contrato de entrada desde Lobby Service (HTTP interno) - espejo de su SalaResponse.
 * Cada servicio define su propio DTO para el mismo contrato JSON (contratos versionados
 * de forma independiente, sin libreria compartida, ver seccion 13 del Inception doc).
 */
public record SalaRosterDto(
        String codigo,
        String anfitrionUserId,
        boolean publica,
        int maxJugadores,
        String estado,
        List<JugadorDto> jugadores
) {
}
