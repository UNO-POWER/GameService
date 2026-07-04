package com.unopower.gameservice.dto;

import com.unopower.gameservice.model.Carta;
import com.unopower.gameservice.model.Color;

public record JugarCartaRequest(Carta carta, Color colorElegido) {
}
