package com.unopower.gameservice.model;

import com.unopower.gameservice.service.JugadaInvalidaException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Motor de una partida de UNO (HU-03) + power-ups (HU-04). Aggregate root: cada instancia
 * vive en memoria del Game Service (ConcurrentHashMap&lt;codigo,Partida&gt; en PartidaRegistry)
 * y serializa sus propias mutaciones con "synchronized" para evitar condiciones de carrera
 * entre jugadas simultaneas de distintos clientes sobre la misma sala.
 */
public class Partida {

    private static final int CARTAS_INICIALES = 7;
    private static final int TURNOS_PARA_POWERUP = 3;
    private static final Random RANDOM = new Random();
    private static final Color[] COLORES_JUGABLES = {Color.ROJO, Color.AMARILLO, Color.VERDE, Color.AZUL};

    private final String codigo;
    private final List<Jugador> jugadores;
    private final Deque<Carta> mazo;
    private final Deque<Carta> descarte = new ArrayDeque<>();

    private int turnoIndex = 0;
    private int direccion = 1;
    private Color colorActual;
    private EstadoPartida estado = EstadoPartida.EN_CURSO;
    private String ganadorUserId;
    private PowerUpActivo powerUpActivo;
    private int turnosDesdeUltimoPowerUp = 0;

    public Partida(String codigo, List<Jugador> jugadores, Deque<Carta> mazoBarajado) {
        if (jugadores.size() < 2) {
            throw new IllegalArgumentException("una partida necesita al menos 2 jugadores");
        }
        this.codigo = codigo;
        this.jugadores = jugadores;
        this.mazo = mazoBarajado;
        repartirCartasIniciales();
        destaparPrimeraCartaDescarte();
    }

    private void repartirCartasIniciales() {
        for (int i = 0; i < CARTAS_INICIALES; i++) {
            for (Jugador jugador : jugadores) {
                jugador.getMano().add(mazo.pollFirst());
            }
        }
    }

    private void destaparPrimeraCartaDescarte() {
        Carta primera;
        do {
            primera = mazo.pollFirst();
            if (primera.tipo() == TipoCarta.COMODIN_MAS_CUATRO) {
                mazo.addLast(primera); // no se permite arrancar con +4, se devuelve al fondo
                primera = null;
            }
        } while (primera == null);
        descarte.addLast(primera);
        colorActual = primera.esComodin() ? colorAleatorio() : primera.color();
    }

    public synchronized void jugarCarta(String userId, Carta cartaJugada, Color colorElegido) {
        validarEnCurso();
        Jugador jugador = validarTurnoYObtenerJugador(userId);

        if (!jugador.getMano().contains(cartaJugada)) {
            throw new JugadaInvalidaException("no tienes esa carta en tu mano");
        }
        Carta tope = descarte.peekLast();
        if (!cartaJugada.esJugableSobre(tope, colorActual)) {
            throw new JugadaInvalidaException("esa carta no es valida sobre el descarte actual");
        }

        jugador.getMano().remove(cartaJugada);
        descarte.addLast(cartaJugada);
        colorActual = cartaJugada.esComodin()
                ? (colorElegido != null ? colorElegido : colorAleatorio())
                : cartaJugada.color();

        if (jugador.getMano().size() != 1) {
            jugador.setDijoUno(false);
        }

        if (jugador.getMano().isEmpty()) {
            estado = EstadoPartida.FINALIZADA;
            ganadorUserId = userId;
            return;
        }

        aplicarEfectoYAvanzar(jugador, cartaJugada);
    }

    private void aplicarEfectoYAvanzar(Jugador jugadorQueJuega, Carta cartaJugada) {
        int pasos = 1;
        switch (cartaJugada.tipo()) {
            case SALTAR -> pasos = 2;
            case REVERSA -> {
                direccion *= -1;
                pasos = jugadoresActivos() == 2 ? 2 : 1;
            }
            case MAS_DOS -> {
                pasos = 2;
                aplicarRoboAlSiguiente(jugadorQueJuega, 2);
            }
            case COMODIN_MAS_CUATRO -> {
                pasos = 2;
                aplicarRoboAlSiguiente(jugadorQueJuega, 4);
            }
            default -> pasos = 1;
        }
        avanzarTurno(pasos);
    }

    private void aplicarRoboAlSiguiente(Jugador jugadorQueJuega, int cantidadBase) {
        int cantidad = jugadorQueJuega.consumirDuplicadorSiActivo() ? cantidadBase * 2 : cantidadBase;
        Jugador siguiente = jugadores.get(siguienteIndice());
        if (!siguiente.consumirEscudoSiActivo()) {
            robarCartas(siguiente, cantidad);
        }
    }

    public synchronized void robarCartaPorTurno(String userId) {
        validarEnCurso();
        Jugador jugador = validarTurnoYObtenerJugador(userId);
        robarCartas(jugador, 1);
        avanzarTurno(1);
    }

    public synchronized void decirUno(String userId) {
        Jugador jugador = obtenerJugador(userId);
        jugador.setDijoUno(true);
    }

    public synchronized PowerUpActivo generarPowerUpSiCorresponde() {
        turnosDesdeUltimoPowerUp++;
        if (powerUpActivo == null && turnosDesdeUltimoPowerUp >= TURNOS_PARA_POWERUP) {
            PowerUpTipo[] tipos = PowerUpTipo.values();
            powerUpActivo = new PowerUpActivo(UUID.randomUUID().toString(), tipos[RANDOM.nextInt(tipos.length)]);
            turnosDesdeUltimoPowerUp = 0;
            return powerUpActivo;
        }
        return null;
    }

    /**
     * HU-04 EC-03: primer click gana, resuelto con compare-and-set atomico (sin lock de
     * partida) para que la resolucion de la carrera no dependa del orden de llegada al monitor.
     */
    public boolean capturarPowerUp(String userId, String powerUpId) {
        PowerUpActivo actual = powerUpActivo;
        if (actual == null || !actual.getId().equals(powerUpId)) {
            return false;
        }
        boolean gano = actual.intentarCapturar(userId);
        if (gano) {
            obtenerJugador(userId).getPowerUpsInventario().add(actual.getTipo());
            synchronized (this) {
                if (powerUpActivo == actual) {
                    powerUpActivo = null;
                }
            }
        }
        return gano;
    }

    public synchronized Optional<List<Carta>> usarPowerUp(String userId, PowerUpTipo tipo, String objetivoUserId) {
        Jugador jugador = obtenerJugador(userId);
        if (!jugador.getPowerUpsInventario().remove(tipo)) {
            throw new JugadaInvalidaException("no tienes ese power-up disponible");
        }
        switch (tipo) {
            case ROBO_FORZADO -> {
                Jugador objetivo = obtenerJugador(objetivoUserId);
                if (!objetivo.consumirEscudoSiActivo()) {
                    robarCartas(objetivo, 2);
                }
            }
            case SALTO_SELECTIVO -> obtenerJugador(objetivoUserId).marcarSaltado();
            case DUPLICADOR -> jugador.activarDuplicador();
            case ESCUDO -> jugador.activarEscudo();
            case ESPIA -> {
                return Optional.of(List.copyOf(obtenerJugador(objetivoUserId).getMano()));
            }
        }
        return Optional.empty();
    }

    public synchronized void marcarAbandonado(String userId) {
        Jugador jugador = obtenerJugador(userId);
        jugador.marcarAbandonado();
        if (jugadoresActivos() < 2 && estado == EstadoPartida.EN_CURSO) {
            estado = EstadoPartida.FINALIZADA;
            ganadorUserId = jugadores.stream().filter(j -> !j.isAbandonado())
                    .map(Jugador::getUserId).findFirst().orElse(null);
        }
    }

    private void robarCartas(Jugador jugador, int cantidad) {
        for (int i = 0; i < cantidad; i++) {
            if (mazo.isEmpty()) {
                reabastecerMazoDesdeDescarte();
            }
            if (mazo.isEmpty()) {
                break; // mazo y descarte agotados (caso extremo, no deberia ocurrir en una partida normal)
            }
            jugador.getMano().add(mazo.pollFirst());
        }
    }

    private void reabastecerMazoDesdeDescarte() {
        if (descarte.size() <= 1) {
            return;
        }
        Carta tope = descarte.pollLast();
        List<Carta> resto = new ArrayList<>(descarte);
        descarte.clear();
        descarte.addLast(tope);
        Collections.shuffle(resto);
        mazo.addAll(resto);
    }

    private void avanzarTurno(int pasos) {
        for (int i = 0; i < pasos; i++) {
            do {
                turnoIndex = Math.floorMod(turnoIndex + direccion, jugadores.size());
            } while (jugadores.get(turnoIndex).isAbandonado());
        }
        while (jugadores.get(turnoIndex).consumirSaltoSiActivo()) {
            do {
                turnoIndex = Math.floorMod(turnoIndex + direccion, jugadores.size());
            } while (jugadores.get(turnoIndex).isAbandonado());
        }
    }

    private int siguienteIndice() {
        int idx = turnoIndex;
        do {
            idx = Math.floorMod(idx + direccion, jugadores.size());
        } while (jugadores.get(idx).isAbandonado());
        return idx;
    }

    private long jugadoresActivos() {
        return jugadores.stream().filter(j -> !j.isAbandonado()).count();
    }

    private Color colorAleatorio() {
        return COLORES_JUGABLES[RANDOM.nextInt(COLORES_JUGABLES.length)];
    }

    private Jugador validarTurnoYObtenerJugador(String userId) {
        Jugador jugador = obtenerJugador(userId);
        if (!jugadores.get(turnoIndex).getUserId().equals(userId)) {
            throw new JugadaInvalidaException("no es tu turno");
        }
        return jugador;
    }

    private void validarEnCurso() {
        if (estado != EstadoPartida.EN_CURSO) {
            throw new JugadaInvalidaException("la partida ya finalizo");
        }
    }

    public Jugador obtenerJugador(String userId) {
        return jugadores.stream()
                .filter(j -> j.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new JugadaInvalidaException("jugador no pertenece a esta partida"));
    }

    public String getCodigo() {
        return codigo;
    }

    public List<Jugador> getJugadores() {
        return jugadores;
    }

    public String getTurnoActualUserId() {
        return jugadores.get(turnoIndex).getUserId();
    }

    public Carta getCartaSuperior() {
        return descarte.peekLast();
    }

    public Color getColorActual() {
        return colorActual;
    }

    public int getDireccion() {
        return direccion;
    }

    public EstadoPartida getEstado() {
        return estado;
    }

    public String getGanadorUserId() {
        return ganadorUserId;
    }

    public PowerUpActivo getPowerUpActivo() {
        return powerUpActivo;
    }
}
