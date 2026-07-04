package com.unopower.gameservice.config;

import java.security.Principal;

/**
 * Identidad del jugador conectado, resuelta desde el JWT en el CONNECT del STOMP
 * (ver StompAuthChannelInterceptor). getName() = userId, usado por Spring para
 * enrutar destinos /user/** (convertAndSendToUser).
 */
public class UsuarioPrincipal implements Principal {

    private final String userId;
    private final String username;

    public UsuarioPrincipal(String userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    @Override
    public String getName() {
        return userId;
    }

    public String getUsername() {
        return username;
    }
}
