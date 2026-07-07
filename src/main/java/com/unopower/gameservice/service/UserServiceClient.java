package com.unopower.gameservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HU-03/HU-07: al terminar una partida, notifica a User Service el resultado de cada
 * jugador para actualizar partidas jugadas/ganadas/racha. Comunicacion asincrona
 * best-effort (seccion 12.b): si User Service esta caido, no bloquea ni revierte el fin
 * de la partida (EC-04, aislamiento de fallos).
 */
@Component
public class UserServiceClient {

    private static final Logger log = LoggerFactory.getLogger(UserServiceClient.class);

    private final RestClient restClient;

    public UserServiceClient(@Value("${services.user-service.url}") String userServiceUrl,
                              @Value("${internal.api-key}") String internalApiKey) {
        this.restClient = RestClient.builder()
                .baseUrl(userServiceUrl)
                .defaultHeader("X-Internal-Key", internalApiKey)
                .build();
    }

    public void notificarResultado(String userId, boolean gano) {
        try {
            restClient.post()
                    .uri("/internal/usuarios/{userId}/resultado-partida", userId)
                    .body(new ResultadoPartidaRequest(gano))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("no se pudo notificar resultado a User Service, userId={} error={}", userId, ex.getMessage());
        }
    }

    private record ResultadoPartidaRequest(boolean gano) {
    }
}
