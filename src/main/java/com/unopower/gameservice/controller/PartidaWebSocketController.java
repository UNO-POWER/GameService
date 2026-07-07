package com.unopower.gameservice.controller;

import com.unopower.gameservice.dto.CapturarPowerUpRequest;
import com.unopower.gameservice.dto.ErrorDto;
import com.unopower.gameservice.dto.JugarCartaRequest;
import com.unopower.gameservice.dto.UsarPowerUpRequest;
import com.unopower.gameservice.service.JugadaInvalidaException;
import com.unopower.gameservice.service.PartidaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * EC-01/EC-05: acciones de partida via WebSocket directo al Game Service (sin Gateway).
 * La identidad viene del Principal resuelto en el CONNECT (StompAuthChannelInterceptor),
 * nunca de lo que el cliente declare en el payload - asi el servidor es autoritativo
 * sobre quien juega, sin importar lo que el frontend intente enviar.
 */
@Controller
public class PartidaWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(PartidaWebSocketController.class);

    private final PartidaService partidaService;

    public PartidaWebSocketController(PartidaService partidaService) {
        this.partidaService = partidaService;
    }

    @MessageMapping("/partidas/{codigo}/sincronizar")
    public void sincronizar(@DestinationVariable String codigo) {
        partidaService.sincronizar(codigo);
    }

    @MessageMapping("/partidas/{codigo}/jugar")
    public void jugar(@DestinationVariable String codigo, @Payload JugarCartaRequest request, Principal principal) {
        partidaService.jugarCarta(codigo, principal.getName(), request.carta(), request.colorElegido());
    }

    @MessageMapping("/partidas/{codigo}/robar")
    public void robar(@DestinationVariable String codigo, Principal principal) {
        partidaService.robar(codigo, principal.getName());
    }

    @MessageMapping("/partidas/{codigo}/decir-uno")
    public void decirUno(@DestinationVariable String codigo, Principal principal) {
        partidaService.decirUno(codigo, principal.getName());
    }

    @MessageMapping("/partidas/{codigo}/powerups/capturar")
    public void capturarPowerUp(@DestinationVariable String codigo, @Payload CapturarPowerUpRequest request,
                                 Principal principal) {
        partidaService.capturarPowerUp(codigo, principal.getName(), request.powerUpId());
    }

    @MessageMapping("/partidas/{codigo}/powerups/usar")
    public void usarPowerUp(@DestinationVariable String codigo, @Payload UsarPowerUpRequest request,
                             Principal principal) {
        partidaService.usarPowerUp(codigo, principal.getName(), request.tipo(), request.objetivoUserId());
    }

    @MessageExceptionHandler(JugadaInvalidaException.class)
    @SendToUser("/queue/errores")
    public ErrorDto manejarJugadaInvalida(JugadaInvalidaException ex) {
        log.warn("jugada rechazada: {}", ex.getMessage());
        return new ErrorDto(ex.getMessage());
    }

    /**
     * KPI-4 (HU-11): los errores inesperados (no jugadas invalidas, que son parte del flujo
     * anti-trampa) cuentan como error critico de partida y se responden con mensaje generico.
     */
    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errores")
    public ErrorDto manejarErrorInesperado(Exception ex) {
        log.error("error inesperado procesando accion de partida", ex);
        partidaService.registrarErrorCritico();
        return new ErrorDto("Ocurrio un error inesperado. Intenta de nuevo.");
    }
}
