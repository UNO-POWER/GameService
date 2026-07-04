package com.unopower.gameservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Unico punto WebSocket del sistema (ver seccion 12.b del Inception doc): el cliente conecta
 * una sola vez a /ws-game y lo reutiliza durante todo el ciclo de vida de la sala:
 *   /topic/sala/{codigo}/lobby -> roster de jugadores mientras se espera en el lobby (HU-01/HU-02)
 *   /topic/sala/{codigo}/chat  -> chat en tiempo real (HU-05, Sprint 2)
 *   /topic/sala/{codigo}/juego -> eventos de la partida (HU-03/HU-04, Sprint 2)
 * No pasa por el API Gateway tras el handshake inicial, para minimizar latencia (EC-01).
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(JwtProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-game")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
