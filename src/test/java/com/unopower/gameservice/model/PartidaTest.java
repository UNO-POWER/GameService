package com.unopower.gameservice.model;

import com.unopower.gameservice.service.JugadaInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HU-03/HU-04: reglas del motor con mazos controlados (sin barajar) para que cada
 * escenario Gherkin del Inception sea determinista.
 */
class PartidaTest {

    private static final String A = "user-a";
    private static final String B = "user-b";
    private static final String C = "user-c";

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Mazo determinista: 7 cartas por jugador (intercaladas), luego descarte, luego pila de robo. */
    private Partida partidaDeDos(List<Carta> manoA, List<Carta> manoB, Carta descarte, Carta... robo) {
        Deque<Carta> mazo = new ArrayDeque<>();
        for (int i = 0; i < 7; i++) {
            mazo.addLast(manoA.get(i));
            mazo.addLast(manoB.get(i));
        }
        mazo.addLast(descarte);
        for (Carta c : robo) {
            mazo.addLast(c);
        }
        List<Jugador> jugadores = List.of(new Jugador(A, "Ana"), new Jugador(B, "Beto"));
        return new Partida("SALA01", jugadores, mazo);
    }

    /** Mano de relleno: 7 verdes numericos que no interfieren con el descarte rojo. */
    private List<Carta> manoRelleno() {
        List<Carta> mano = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            mano.add(Carta.numero(Color.VERDE, i));
        }
        return mano;
    }

    private List<Carta> manoConPrimera(Carta primera) {
        List<Carta> mano = manoRelleno();
        mano.set(0, primera);
        return mano;
    }

    private Carta descarteRojo5() {
        return Carta.numero(Color.ROJO, 5);
    }

    private Carta[] roboExtra(int cantidad) {
        Carta[] extra = new Carta[cantidad];
        for (int i = 0; i < cantidad; i++) {
            extra[i] = Carta.numero(Color.AMARILLO, (i % 9) + 1);
        }
        return extra;
    }

    // ── HU-03: inicio de partida ────────────────────────────────────────────

    @Test
    @DisplayName("al iniciar, cada jugador recibe 7 cartas y hay carta en el descarte")
    void inicioReparteSieteCartas() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(10));
        assertEquals(7, p.getJugadores().get(0).getMano().size());
        assertEquals(7, p.getJugadores().get(1).getMano().size());
        assertEquals(descarteRojo5(), p.getCartaSuperior());
        assertEquals(Color.ROJO, p.getColorActual());
        assertEquals(A, p.getTurnoActualUserId());
        assertEquals(EstadoPartida.EN_CURSO, p.getEstado());
    }

    @Test
    @DisplayName("no se permite crear una partida con menos de 2 jugadores")
    void partidaConUnJugadorFalla() {
        Deque<Carta> mazo = new ArrayDeque<>(List.of(Carta.numero(Color.ROJO, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Partida("X", List.of(new Jugador(A, "Ana")), mazo));
    }

    // ── EC-05: validaciones anti-trampa ─────────────────────────────────────

    @Test
    @DisplayName("rechaza jugar fuera de turno")
    void rechazaJugarFueraDeTurno() {
        Partida p = partidaDeDos(manoRelleno(), manoConPrimera(Carta.numero(Color.ROJO, 3)), descarteRojo5(), roboExtra(4));
        JugadaInvalidaException ex = assertThrows(JugadaInvalidaException.class,
                () -> p.jugarCarta(B, Carta.numero(Color.ROJO, 3), null));
        assertTrue(ex.getMessage().contains("turno"));
    }

    @Test
    @DisplayName("rechaza jugar una carta que el jugador no posee")
    void rechazaCartaNoPoseida() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        assertThrows(JugadaInvalidaException.class,
                () -> p.jugarCarta(A, Carta.numero(Color.ROJO, 9), null));
    }

    @Test
    @DisplayName("rechaza una carta no jugable sobre el descarte")
    void rechazaCartaNoJugable() {
        // A tiene VERDE 1..7; descarte ROJO 5: VERDE 1 no coincide ni en color ni numero
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        assertThrows(JugadaInvalidaException.class,
                () -> p.jugarCarta(A, Carta.numero(Color.VERDE, 1), null));
    }

    @Test
    @DisplayName("rechaza jugadas cuando la partida ya finalizo")
    void rechazaJugadasTrasFinalizar() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.marcarAbandonado(B); // con 2 jugadores, finaliza y gana A
        assertEquals(EstadoPartida.FINALIZADA, p.getEstado());
        assertThrows(JugadaInvalidaException.class,
                () -> p.jugarCarta(A, Carta.numero(Color.VERDE, 1), null));
    }

    // ── HU-03: jugadas y cartas especiales ──────────────────────────────────

    @Test
    @DisplayName("jugada valida: carta al descarte, color actualizado y turno avanza")
    void jugadaValidaAvanzaTurno() {
        Carta rojo3 = Carta.numero(Color.ROJO, 3);
        Partida p = partidaDeDos(manoConPrimera(rojo3), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.jugarCarta(A, rojo3, null);
        assertEquals(rojo3, p.getCartaSuperior());
        assertEquals(Color.ROJO, p.getColorActual());
        assertEquals(B, p.getTurnoActualUserId());
        assertEquals(6, p.obtenerJugador(A).getMano().size());
    }

    @Test
    @DisplayName("mismo numero distinto color es jugable")
    void mismoNumeroDistintoColorEsJugable() {
        Carta verde5 = Carta.numero(Color.VERDE, 5);
        Partida p = partidaDeDos(manoConPrimera(verde5), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.jugarCarta(A, verde5, null);
        assertEquals(Color.VERDE, p.getColorActual());
    }

    @Test
    @DisplayName("SALTAR con 2 jugadores devuelve el turno al que la jugo")
    void saltarConDosJugadores() {
        Carta saltar = Carta.especial(Color.ROJO, TipoCarta.SALTAR);
        Partida p = partidaDeDos(manoConPrimera(saltar), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.jugarCarta(A, saltar, null);
        assertEquals(A, p.getTurnoActualUserId());
    }

    @Test
    @DisplayName("REVERSA con 2 jugadores actua como saltar")
    void reversaConDosJugadores() {
        Carta reversa = Carta.especial(Color.ROJO, TipoCarta.REVERSA);
        Partida p = partidaDeDos(manoConPrimera(reversa), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.jugarCarta(A, reversa, null);
        assertEquals(A, p.getTurnoActualUserId());
        assertEquals(-1, p.getDireccion());
    }

    @Test
    @DisplayName("REVERSA con 3 jugadores invierte la direccion")
    void reversaConTresJugadoresInvierte() {
        Deque<Carta> mazo = new ArrayDeque<>();
        Carta reversa = Carta.especial(Color.ROJO, TipoCarta.REVERSA);
        List<Carta> manoA = manoConPrimera(reversa);
        List<Carta> resto = manoRelleno();
        for (int i = 0; i < 7; i++) {
            mazo.addLast(manoA.get(i));
            mazo.addLast(resto.get(i));
            mazo.addLast(resto.get(i));
        }
        mazo.addLast(descarteRojo5());
        List<Jugador> tres = List.of(new Jugador(A, "Ana"), new Jugador(B, "Beto"), new Jugador(C, "Carla"));
        Partida p = new Partida("SALA03", tres, mazo);

        p.jugarCarta(A, reversa, null);
        // direccion invertida: el siguiente es C, no B
        assertEquals(C, p.getTurnoActualUserId());
    }

    @Test
    @DisplayName("Gherkin HU-03: +2 hace robar 2 al siguiente y pierde su turno")
    void masDosRobaYSalta() {
        Carta masDos = Carta.especial(Color.ROJO, TipoCarta.MAS_DOS);
        Partida p = partidaDeDos(manoConPrimera(masDos), manoRelleno(), descarteRojo5(), roboExtra(6));
        p.jugarCarta(A, masDos, null);
        assertEquals(9, p.obtenerJugador(B).getMano().size());
        assertEquals(A, p.getTurnoActualUserId());
    }

    @Test
    @DisplayName("Gherkin HU-03: +4 exige color, el siguiente roba 4 y pierde su turno")
    void comodinMasCuatro() {
        Carta masCuatro = Carta.comodinMasCuatro();
        Partida p = partidaDeDos(manoConPrimera(masCuatro), manoRelleno(), descarteRojo5(), roboExtra(8));

        assertThrows(JugadaInvalidaException.class, () -> p.jugarCarta(A, masCuatro, null));

        p.jugarCarta(A, masCuatro, Color.AZUL);
        assertEquals(Color.AZUL, p.getColorActual());
        assertEquals(11, p.obtenerJugador(B).getMano().size());
        assertEquals(A, p.getTurnoActualUserId());
    }

    @Test
    @DisplayName("comodin simple cambia color sin hacer robar")
    void comodinCambiaColor() {
        Carta comodin = Carta.comodin();
        Partida p = partidaDeDos(manoConPrimera(comodin), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.jugarCarta(A, comodin, Color.AMARILLO);
        assertEquals(Color.AMARILLO, p.getColorActual());
        assertEquals(7, p.obtenerJugador(B).getMano().size());
        assertEquals(B, p.getTurnoActualUserId());
    }

    @Test
    @DisplayName("robar carta agrega una a la mano y cede el turno")
    void robarCartaCedeTurno() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.robarCartaPorTurno(A);
        assertEquals(8, p.obtenerJugador(A).getMano().size());
        assertEquals(B, p.getTurnoActualUserId());
    }

    // ── Gherkin HU-03: deteccion de ganador ─────────────────────────────────

    @Test
    @DisplayName("el jugador que se queda sin cartas gana y la partida finaliza")
    void deteccionDeGanador() {
        Carta rojo3 = Carta.numero(Color.ROJO, 3);
        Partida p = partidaDeDos(manoConPrimera(rojo3), manoRelleno(), descarteRojo5(), roboExtra(4));
        Jugador a = p.obtenerJugador(A);
        a.setDijoUno(true);
        a.getMano().retainAll(List.of(rojo3)); // dejarlo con una sola carta

        p.jugarCarta(A, rojo3, null);

        assertEquals(EstadoPartida.FINALIZADA, p.getEstado());
        assertEquals(A, p.getGanadorUserId());
        assertTrue(p.obtenerJugador(A).getMano().isEmpty());
    }

    @Test
    @DisplayName("decir UNO marca la bandera y jugar sin quedar en una carta la limpia")
    void decirUno() {
        Partida p = partidaDeDos(manoConPrimera(Carta.numero(Color.ROJO, 3)), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.decirUno(A);
        assertTrue(p.obtenerJugador(A).isDijoUno());
        p.jugarCarta(A, Carta.numero(Color.ROJO, 3), null); // quedan 6 cartas
        assertFalse(p.obtenerJugador(A).isDijoUno());
    }

    // ── HU-04: power-ups ────────────────────────────────────────────────────

    @Test
    @DisplayName("un power-up aparece cada 3 turnos")
    void powerUpApareceCadaTresTurnos() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        assertNull(p.generarPowerUpSiCorresponde());
        assertNull(p.generarPowerUpSiCorresponde());
        assertNotNull(p.generarPowerUpSiCorresponde());
        assertNotNull(p.getPowerUpActivo());
    }

    @Test
    @DisplayName("Gherkin HU-04: en captura simultanea solo el primero obtiene el power-up")
    void capturaConcurrenteSoloUnoGana() throws InterruptedException {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.generarPowerUpSiCorresponde();
        p.generarPowerUpSiCorresponde();
        PowerUpActivo powerUp = p.generarPowerUpSiCorresponde();

        int hilos = 16;
        List<Thread> threads = new ArrayList<>();
        List<Boolean> resultados = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < hilos; i++) {
            String user = (i % 2 == 0) ? A : B;
            threads.add(new Thread(() -> resultados.add(p.capturarPowerUp(user, powerUp.getId()))));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }

        long ganadores = resultados.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, ganadores);
        assertNull(p.getPowerUpActivo()); // consumido
        int enInventarios = p.obtenerJugador(A).getPowerUpsInventario().size()
                + p.obtenerJugador(B).getPowerUpsInventario().size();
        assertEquals(1, enInventarios);
    }

    @Test
    @DisplayName("usar un power-up que no se posee es rechazado")
    void usarPowerUpSinInventario() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        assertThrows(JugadaInvalidaException.class,
                () -> p.usarPowerUp(A, PowerUpTipo.ESCUDO, null));
    }

    @Test
    @DisplayName("Gherkin HU-04: el escudo se consume y bloquea un +4")
    void escudoBloqueaMasCuatro() {
        Carta masCuatro = Carta.comodinMasCuatro();
        Partida p = partidaDeDos(manoConPrimera(masCuatro), manoRelleno(), descarteRojo5(), roboExtra(8));
        p.obtenerJugador(B).getPowerUpsInventario().add(PowerUpTipo.ESCUDO);
        p.usarPowerUp(B, PowerUpTipo.ESCUDO, null);
        assertTrue(p.obtenerJugador(B).isEscudoActivo());

        p.jugarCarta(A, masCuatro, Color.AZUL);

        assertEquals(7, p.obtenerJugador(B).getMano().size()); // no robo nada
        assertFalse(p.obtenerJugador(B).isEscudoActivo());     // escudo consumido
    }

    @Test
    @DisplayName("el duplicador duplica el robo del siguiente +2")
    void duplicadorDuplicaRobo() {
        Carta masDos = Carta.especial(Color.ROJO, TipoCarta.MAS_DOS);
        Partida p = partidaDeDos(manoConPrimera(masDos), manoRelleno(), descarteRojo5(), roboExtra(8));
        p.obtenerJugador(A).getPowerUpsInventario().add(PowerUpTipo.DUPLICADOR);
        p.usarPowerUp(A, PowerUpTipo.DUPLICADOR, null);

        p.jugarCarta(A, masDos, null);

        assertEquals(11, p.obtenerJugador(B).getMano().size()); // 7 + 4 (2x2)
    }

    @Test
    @DisplayName("el espia devuelve una copia de la mano del objetivo")
    void espiaDevuelveMano() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.obtenerJugador(A).getPowerUpsInventario().add(PowerUpTipo.ESPIA);
        Optional<List<Carta>> mano = p.usarPowerUp(A, PowerUpTipo.ESPIA, B);
        assertTrue(mano.isPresent());
        assertEquals(7, mano.get().size());
    }

    @Test
    @DisplayName("el salto selectivo hace que el objetivo pierda su proximo turno")
    void saltoSelectivo() {
        Carta rojo3 = Carta.numero(Color.ROJO, 3);
        Partida p = partidaDeDos(manoConPrimera(rojo3), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.obtenerJugador(A).getPowerUpsInventario().add(PowerUpTipo.SALTO_SELECTIVO);
        p.usarPowerUp(A, PowerUpTipo.SALTO_SELECTIVO, B);

        p.jugarCarta(A, rojo3, null); // el turno deberia saltarse a B y volver a A

        assertEquals(A, p.getTurnoActualUserId());
    }

    // ── HU-08: abandono ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Gherkin HU-08: si un jugador abandona y queda uno solo, la partida finaliza")
    void abandonoFinalizaConUnJugador() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.marcarAbandonado(B);
        assertEquals(EstadoPartida.FINALIZADA, p.getEstado());
        assertEquals(A, p.getGanadorUserId());
    }

    @Test
    @DisplayName("con 3 jugadores, el abandono de uno no finaliza y su turno se salta")
    void abandonoConTresContinua() {
        Deque<Carta> mazo = new ArrayDeque<>();
        Carta rojo3 = Carta.numero(Color.ROJO, 3);
        List<Carta> manoA = manoConPrimera(rojo3);
        List<Carta> resto = manoRelleno();
        for (int i = 0; i < 7; i++) {
            mazo.addLast(manoA.get(i));
            mazo.addLast(resto.get(i));
            mazo.addLast(resto.get(i));
        }
        mazo.addLast(descarteRojo5());
        Partida p = new Partida("SALA03",
                List.of(new Jugador(A, "Ana"), new Jugador(B, "Beto"), new Jugador(C, "Carla")), mazo);

        p.marcarAbandonado(B);
        assertEquals(EstadoPartida.EN_CURSO, p.getEstado());

        p.jugarCarta(A, rojo3, null);
        assertEquals(C, p.getTurnoActualUserId()); // B abandonado se salta
    }

    @Test
    @DisplayName("el fin de partida se notifica exactamente una vez (CAS)")
    void finNotificadoUnaVez() {
        Partida p = partidaDeDos(manoRelleno(), manoRelleno(), descarteRojo5(), roboExtra(4));
        p.marcarAbandonado(B);
        assertTrue(p.marcarFinNotificado());
        assertFalse(p.marcarFinNotificado());
    }

    @Test
    @DisplayName("cuando el mazo se agota se reabastece desde el descarte")
    void reabasteceMazoDesdeDescarte() {
        // robo con solo 1 carta extra: el segundo robo obliga a reciclar el descarte
        Carta rojo3 = Carta.numero(Color.ROJO, 3);
        Partida p = partidaDeDos(manoConPrimera(rojo3), manoRelleno(), descarteRojo5(), roboExtra(1));
        p.jugarCarta(A, rojo3, null); // descarte ahora tiene 2 cartas; A queda con 6
        p.robarCartaPorTurno(B);       // consume la unica del mazo
        p.robarCartaPorTurno(A);       // fuerza reabastecimiento desde el descarte
        assertEquals(7, p.obtenerJugador(A).getMano().size()); // 6 + 1 robada del descarte reciclado
    }
}
