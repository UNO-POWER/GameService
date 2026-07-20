package com.unopower.gameservice.controller;

import com.unopower.gameservice.config.UsuarioPrincipal;
import com.unopower.gameservice.dto.AnuncioVozDto;
import com.unopower.gameservice.dto.SenalVozDto;
import com.unopower.gameservice.dto.SenalVozRequest;
import com.unopower.gameservice.model.Partida;
import com.unopower.gameservice.service.PartidaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

/**
 * Voz en tiempo real (WebRTC P2P mesh): este controller SOLO retransmite la senalizacion
 * (offers/answers/candidatos ICE) entre jugadores de la misma partida. El audio nunca pasa
 * por el servidor: fluye cifrado (DTLS-SRTP) directo entre navegadores. La identidad del
 * emisor sale siempre del Principal (JWT del CONNECT), nunca del payload.
 */
@Controller
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    /** SDP tipico pesa unos pocos KB; este tope corta abusos sin afectar senales legitimas. */
    static final int MAX_DATOS = 20_000;

    private final SimpMessagingTemplate messagingTemplate;
    private final PartidaRegistry registry;

    public VoiceController(SimpMessagingTemplate messagingTemplate, PartidaRegistry registry) {
        this.messagingTemplate = messagingTemplate;
        this.registry = registry;
    }

    /** Entrada/salida del canal de voz: se difunde a toda la sala para armar/deshacer la malla. */
    @MessageMapping("/salas/{codigo}/voice/anuncio")
    public void anunciar(@DestinationVariable String codigo, @Payload Map<String, String> body,
                          UsuarioPrincipal principal) {
        if (!esJugadorDePartida(codigo, principal.getName())) {
            return;
        }
        String tipo = "salida".equals(body.get("tipo")) ? "salida" : "presente";
        messagingTemplate.convertAndSend("/topic/sala/" + codigo + "/voice",
                new AnuncioVozDto(principal.getName(), principal.getUsername(), tipo));
    }

    /** Retransmision dirigida de una senal WebRTC al objetivo (que debe ser de la misma partida). */
    @MessageMapping("/salas/{codigo}/voice/senal")
    public void retransmitirSenal(@DestinationVariable String codigo, @Payload SenalVozRequest request,
                                   UsuarioPrincipal principal) {
        if (request.objetivoUserId() == null || request.tipo() == null || request.datos() == null
                || request.datos().length() > MAX_DATOS) {
            return;
        }
        if (!esJugadorDePartida(codigo, principal.getName())
                || !esJugadorDePartida(codigo, request.objetivoUserId())) {
            log.warn("senal de voz descartada codigo={} de={} para={}", codigo, principal.getName(),
                    request.objetivoUserId());
            return;
        }
        messagingTemplate.convertAndSendToUser(request.objetivoUserId(), "/queue/voice",
                new SenalVozDto(principal.getName(), principal.getUsername(), request.tipo(), request.datos()));
    }

    private boolean esJugadorDePartida(String codigo, String userId) {
        if (!registry.existe(codigo)) {
            return false;
        }
        Partida partida = registry.obtener(codigo);
        return partida.getJugadores().stream().anyMatch(j -> j.getUserId().equals(userId));
    }
}
