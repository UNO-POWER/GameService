package com.unopower.gameservice.dto;

import com.unopower.gameservice.model.Carta;
import com.unopower.gameservice.model.PowerUpTipo;

import java.util.List;

public record ManoDto(String codigo, List<Carta> cartas, List<PowerUpTipo> powerUpsInventario) {
}
