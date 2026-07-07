package com.unopower.gameservice.service;

import com.unopower.gameservice.model.Carta;
import com.unopower.gameservice.model.Color;
import com.unopower.gameservice.model.TipoCarta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MazoFactoryTest {

    private final MazoFactory factory = new MazoFactory();

    @Test
    @DisplayName("el mazo estandar de UNO tiene 108 cartas con la composicion correcta")
    void mazoEstandarCompleto() {
        Deque<Carta> mazo = factory.crearMazoBarajado();
        assertEquals(108, mazo.size());

        long comodines = mazo.stream().filter(c -> c.tipo() == TipoCarta.COMODIN).count();
        long masCuatro = mazo.stream().filter(c -> c.tipo() == TipoCarta.COMODIN_MAS_CUATRO).count();
        assertEquals(4, comodines);
        assertEquals(4, masCuatro);

        for (Color color : new Color[]{Color.ROJO, Color.AMARILLO, Color.VERDE, Color.AZUL}) {
            long ceros = mazo.stream().filter(c -> c.color() == color && c.tipo() == TipoCarta.NUMERO && c.valor() == 0).count();
            long numeros = mazo.stream().filter(c -> c.color() == color && c.tipo() == TipoCarta.NUMERO).count();
            long saltar = mazo.stream().filter(c -> c.color() == color && c.tipo() == TipoCarta.SALTAR).count();
            long reversa = mazo.stream().filter(c -> c.color() == color && c.tipo() == TipoCarta.REVERSA).count();
            long masDos = mazo.stream().filter(c -> c.color() == color && c.tipo() == TipoCarta.MAS_DOS).count();
            assertEquals(1, ceros);
            assertEquals(19, numeros); // 1 cero + 2 de cada 1..9
            assertEquals(2, saltar);
            assertEquals(2, reversa);
            assertEquals(2, masDos);
        }
    }
}
