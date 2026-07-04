package com.unopower.gameservice.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Jugador {

    private final String userId;
    private final String username;
    private final List<Carta> mano = new ArrayList<>();
    private final List<PowerUpTipo> powerUpsInventario = new CopyOnWriteArrayList<>();
    private boolean dijoUno = false;
    private boolean escudoActivo = false;
    private boolean duplicadorActivo = false;
    private boolean saltadoProximoTurno = false;
    private boolean abandonado = false;

    public Jugador(String userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public List<Carta> getMano() {
        return mano;
    }

    public List<PowerUpTipo> getPowerUpsInventario() {
        return powerUpsInventario;
    }

    public boolean isDijoUno() {
        return dijoUno;
    }

    public void setDijoUno(boolean dijoUno) {
        this.dijoUno = dijoUno;
    }

    public boolean isEscudoActivo() {
        return escudoActivo;
    }

    public void activarEscudo() {
        this.escudoActivo = true;
    }

    /**
     * @return true si habia escudo y se consumio (el ataque no tiene efecto)
     */
    public boolean consumirEscudoSiActivo() {
        if (escudoActivo) {
            escudoActivo = false;
            return true;
        }
        return false;
    }

    public boolean isDuplicadorActivo() {
        return duplicadorActivo;
    }

    public void activarDuplicador() {
        this.duplicadorActivo = true;
    }

    public boolean consumirDuplicadorSiActivo() {
        if (duplicadorActivo) {
            duplicadorActivo = false;
            return true;
        }
        return false;
    }

    public boolean isSaltadoProximoTurno() {
        return saltadoProximoTurno;
    }

    public void marcarSaltado() {
        this.saltadoProximoTurno = true;
    }

    public boolean consumirSaltoSiActivo() {
        if (saltadoProximoTurno) {
            saltadoProximoTurno = false;
            return true;
        }
        return false;
    }

    public boolean isAbandonado() {
        return abandonado;
    }

    public void marcarAbandonado() {
        this.abandonado = true;
    }
}
