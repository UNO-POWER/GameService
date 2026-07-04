package com.unopower.gameservice.service;

/**
 * EC-05 (Seguridad): el Game Service rechaza toda jugada que no cumpla las reglas del
 * servidor (no es su turno, carta que no posee, carta no jugable sobre el descarte).
 */
public class JugadaInvalidaException extends RuntimeException {
    public JugadaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
