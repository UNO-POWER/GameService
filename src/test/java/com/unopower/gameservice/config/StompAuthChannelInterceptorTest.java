package com.unopower.gameservice.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * EC-05: el WebSocket no pasa por el API Gateway, asi que este interceptor es la unica
 * barrera de autenticacion del canal de juego.
 */
class StompAuthChannelInterceptorTest {

    private static final String SECRET = "un-secreto-de-pruebas-suficientemente-largo-para-hs512-0123456789";

    private StompAuthChannelInterceptor interceptor() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        return new StompAuthChannelInterceptor(props);
    }

    private String tokenValido(String userId, String username) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }

    private Message<byte[]> mensajeConnect(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("un CONNECT con JWT valido resuelve el Principal con userId y username")
    void connectConTokenValido() {
        Message<?> resultado = interceptor().preSend(
                mensajeConnect("Bearer " + tokenValido("user-1", "Ana")), mock(org.springframework.messaging.MessageChannel.class));

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(resultado);
        assertNotNull(accessor.getUser());
        assertEquals("user-1", accessor.getUser().getName());
        assertEquals("Ana", ((UsuarioPrincipal) accessor.getUser()).getUsername());
    }

    @Test
    @DisplayName("un CONNECT sin token es rechazado")
    void connectSinToken() {
        assertThrows(MessagingException.class, () -> interceptor().preSend(
                mensajeConnect(null), mock(org.springframework.messaging.MessageChannel.class)));
    }

    @Test
    @DisplayName("un CONNECT con token invalido es rechazado")
    void connectConTokenInvalido() {
        assertThrows(MessagingException.class, () -> interceptor().preSend(
                mensajeConnect("Bearer no-es-un-jwt"), mock(org.springframework.messaging.MessageChannel.class)));
    }
}
