package com.unopower.gameservice.controller;

import com.unopower.gameservice.dto.MensajeChatDto;
import com.unopower.gameservice.dto.MensajeChatRequest;
import com.unopower.gameservice.config.UsuarioPrincipal;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.Instant;

/**
 * HU-05: chat en tiempo real por sala, aislado por codigo (cada sala tiene su propio
 * topic). El autor viene del Principal autenticado, no del payload del cliente.
 */
@Controller
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    static final int LONGITUD_MAXIMA = 300;

    @MessageMapping("/salas/{codigo}/chat")
    public void enviarMensaje(@DestinationVariable String codigo, @Payload MensajeChatRequest request,
                               UsuarioPrincipal principal) {
        if (request.texto() == null || request.texto().isBlank()) {
            return; // mensajes vacios se descartan en silencio
        }
        String texto = request.texto().strip();
        if (texto.length() > LONGITUD_MAXIMA) {
            texto = texto.substring(0, LONGITUD_MAXIMA);
        }
        MensajeChatDto mensaje = new MensajeChatDto(principal.getName(), principal.getUsername(),
                texto, Instant.now());
        messagingTemplate.convertAndSend("/topic/sala/" + codigo + "/chat", mensaje);
    }
}
