package com.unopower.gameservice.dto;

public record JugadorDto(
        String userId,
        String username,
        boolean esAnfitrion
) {
}
