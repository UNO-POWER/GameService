package com.unopower.gameservice.controller;

import com.unopower.gameservice.dto.SalaRosterDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint interno (servicio a servicio, no pasa por API Gateway) que Lobby Service llama
 * cuando cambia el roster de una sala (HU-01/HU-02). Difunde por WebSocket a los clientes
 * ya conectados a /ws-game y suscritos a /topic/sala/{codigo}/lobby.
 */
@RestController
public class RosterController {

    private static final Logger log = LoggerFactory.getLogger(RosterController.class);

    private final SimpMessagingTemplate messagingTemplate;

    public RosterController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/internal/salas/{codigo}/roster")
    public void actualizarRoster(@PathVariable String codigo, @RequestBody SalaRosterDto roster) {
        log.info("roster actualizado codigo={} jugadores={}", codigo, roster.jugadores().size());
        messagingTemplate.convertAndSend("/topic/sala/" + codigo + "/lobby", roster);
    }
}
