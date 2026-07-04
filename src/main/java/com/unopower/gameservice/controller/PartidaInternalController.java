package com.unopower.gameservice.controller;

import com.unopower.gameservice.dto.IniciarPartidaRequest;
import com.unopower.gameservice.service.PartidaService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Llamado por Lobby Service (servicio a servicio, no expuesto via API Gateway) cuando el
 * anfitrion inicia la partida. Reparte cartas y publica el estado inicial por WebSocket.
 */
@RestController
public class PartidaInternalController {

    private final PartidaService partidaService;

    public PartidaInternalController(PartidaService partidaService) {
        this.partidaService = partidaService;
    }

    @PostMapping("/internal/partidas/{codigo}/iniciar")
    @ResponseStatus(HttpStatus.CREATED)
    public void iniciar(@PathVariable String codigo, @RequestBody IniciarPartidaRequest request) {
        partidaService.iniciarPartida(codigo, request.jugadores());
    }
}
