package com.unopower.gameservice.dto;

import com.unopower.gameservice.model.Carta;

import java.util.List;

public record EspiaResultadoDto(String objetivoUserId, String objetivoUsername, List<Carta> cartas) {
}
