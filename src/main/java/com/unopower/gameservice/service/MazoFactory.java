package com.unopower.gameservice.service;

import com.unopower.gameservice.model.Carta;
import com.unopower.gameservice.model.Color;
import com.unopower.gameservice.model.TipoCarta;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Mazo estandar de UNO: 108 cartas. Por color (Rojo/Amarillo/Verde/Azul): un 0, dos de
 * cada 1-9, dos Saltar, dos Reversa, dos +2 (25 cartas x4 = 100) + 4 Comodin + 4 Comodin+4.
 */
@Component
public class MazoFactory {

    public Deque<Carta> crearMazoBarajado() {
        List<Carta> cartas = new ArrayList<>(108);
        Color[] colores = {Color.ROJO, Color.AMARILLO, Color.VERDE, Color.AZUL};

        for (Color color : colores) {
            cartas.add(Carta.numero(color, 0));
            for (int valor = 1; valor <= 9; valor++) {
                cartas.add(Carta.numero(color, valor));
                cartas.add(Carta.numero(color, valor));
            }
            for (int i = 0; i < 2; i++) {
                cartas.add(Carta.especial(color, TipoCarta.SALTAR));
                cartas.add(Carta.especial(color, TipoCarta.REVERSA));
                cartas.add(Carta.especial(color, TipoCarta.MAS_DOS));
            }
        }
        for (int i = 0; i < 4; i++) {
            cartas.add(Carta.comodin());
            cartas.add(Carta.comodinMasCuatro());
        }

        Collections.shuffle(cartas);
        return new ArrayDeque<>(cartas);
    }
}
