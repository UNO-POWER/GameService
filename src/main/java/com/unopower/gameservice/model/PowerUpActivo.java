package com.unopower.gameservice.model;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Power-up visible en el tablero, pendiente de captura. HU-04 EC-03: "primer click gana,
 * sin condicion de carrera" -> se resuelve con compare-and-set atomico sobre capturadoPor.
 */
public class PowerUpActivo {

    private final String id;
    private final PowerUpTipo tipo;
    private final AtomicReference<String> capturadoPor = new AtomicReference<>();

    public PowerUpActivo(String id, PowerUpTipo tipo) {
        this.id = id;
        this.tipo = tipo;
    }

    public String getId() {
        return id;
    }

    public PowerUpTipo getTipo() {
        return tipo;
    }

    /**
     * @return true si este jugador gano la captura (fue el primero en hacer CAS exitoso)
     */
    public boolean intentarCapturar(String userId) {
        return capturadoPor.compareAndSet(null, userId);
    }

    public String getCapturadoPor() {
        return capturadoPor.get();
    }

    public boolean estaDisponible() {
        return capturadoPor.get() == null;
    }
}
