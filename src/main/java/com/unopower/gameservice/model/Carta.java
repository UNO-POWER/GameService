package com.unopower.gameservice.model;

public record Carta(Color color, TipoCarta tipo, Integer valor) {

    public static Carta numero(Color color, int valor) {
        return new Carta(color, TipoCarta.NUMERO, valor);
    }

    public static Carta especial(Color color, TipoCarta tipo) {
        return new Carta(color, tipo, null);
    }

    public static Carta comodin() {
        return new Carta(Color.COMODIN, TipoCarta.COMODIN, null);
    }

    public static Carta comodinMasCuatro() {
        return new Carta(Color.COMODIN, TipoCarta.COMODIN_MAS_CUATRO, null);
    }

    public boolean esComodin() {
        return tipo == TipoCarta.COMODIN || tipo == TipoCarta.COMODIN_MAS_CUATRO;
    }

    /**
     * Reglas UNO: coincide color, mismo tipo especial (aunque cambie color), mismo numero, o comodin.
     */
    public boolean esJugableSobre(Carta cartaSuperior, Color colorActual) {
        if (esComodin()) {
            return true;
        }
        if (this.color == colorActual) {
            return true;
        }
        if (this.tipo != TipoCarta.NUMERO && this.tipo == cartaSuperior.tipo()) {
            return true;
        }
        return this.tipo == TipoCarta.NUMERO && cartaSuperior.tipo() == TipoCarta.NUMERO
                && this.valor.equals(cartaSuperior.valor());
    }
}
