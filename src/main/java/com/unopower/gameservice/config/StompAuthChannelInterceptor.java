package com.unopower.gameservice.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * EC-05 (Seguridad): el WebSocket conecta directo al Game Service, sin pasar por el API
 * Gateway (ver seccion 12.b), asi que el JWT se valida aqui mismo en el CONNECT. Sin esto,
 * cualquiera podria abrir el socket y jugar suplantando a otro userId.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtProperties jwtProperties;

    public StompAuthChannelInterceptor(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = extraerToken(accessor);
            if (token == null) {
                throw new MessagingException("falta token de autenticacion en el CONNECT");
            }
            try {
                SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
                Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
                Claims claims = jws.getPayload();
                String userId = claims.getSubject();
                String username = claims.get("username", String.class);
                accessor.setUser(new UsuarioPrincipal(userId, username));
            } catch (JwtException | IllegalArgumentException ex) {
                throw new MessagingException("token invalido o expirado", ex);
            }
        }
        return message;
    }

    private String extraerToken(StompHeaderAccessor accessor) {
        List<String> valores = accessor.getNativeHeader("Authorization");
        if (valores == null || valores.isEmpty()) {
            return null;
        }
        String header = valores.get(0);
        return header.startsWith("Bearer ") ? header.substring("Bearer ".length()) : header;
    }
}
