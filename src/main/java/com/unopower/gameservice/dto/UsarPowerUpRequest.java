package com.unopower.gameservice.dto;

import com.unopower.gameservice.model.PowerUpTipo;

public record UsarPowerUpRequest(PowerUpTipo tipo, String objetivoUserId) {
}
