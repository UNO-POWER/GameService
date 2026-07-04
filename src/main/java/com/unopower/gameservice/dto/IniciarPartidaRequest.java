package com.unopower.gameservice.dto;

import java.util.List;

public record IniciarPartidaRequest(List<JugadorInicialDto> jugadores) {
}
