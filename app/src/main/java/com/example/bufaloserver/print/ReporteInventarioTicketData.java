package com.example.bufaloserver.print;

import java.util.ArrayList;
import java.util.List;

/**
 * Datos del ticket "Reporte de Inventario" (estado ACTUAL del stock de todo lo que
 * tiene inventario activado). Lo arma ServidorHttp.imprimirReporteInventario() y lo
 * dibuja {@link EscPosReporteInventarioBuilder}.
 *
 * Reglas (las mismas que usa la pantalla de Inventario):
 *   - sin stock : actual <= 0
 *   - bajo      : 0 < actual <= mínimo
 *   - faltante  : máximo − actual (nunca negativo). Solo si el máximo está definido (> 0).
 */
public class ReporteInventarioTicketData {

    /** Fecha ya formateada, ej. "jueves 01 de octubre 2026". */
    public String fechaLabel;

    public String impresoPor;
    public String rolImpresor;

    public long fechaMillis = System.currentTimeMillis();

    /** Categorías que tienen al menos un ítem con inventario, en el orden del menú. */
    public List<CategoriaInv> categorias = new ArrayList<>();

    public int cantSinStock;
    public int cantBajo;
    public int cantOk;

    /** Ítems con inventario pero sin stock máximo definido (no se puede calcular faltante). */
    public int cantSinMaximo;

    /** Suma de faltantes de los ítems que sí tienen máximo definido. */
    public int totalFaltante;

    public static class CategoriaInv {
        public String nombre;
        public List<ItemInv> items = new ArrayList<>();

        public CategoriaInv(String nombre) {
            this.nombre = nombre;
        }
    }

    public static class ItemInv {
        /** Producto, o "Producto - Variante" si el inventario es por variante. */
        public String nombre;
        public int actual;
        public int minimo;
        public int maximo;

        public ItemInv(String nombre, int actual, int minimo, int maximo) {
            this.nombre = nombre;
            this.actual = actual;
            this.minimo = minimo;
            this.maximo = maximo;
        }

        public boolean tieneMaximo() { return maximo > 0; }
        public boolean sinStock()    { return actual <= 0; }
        public boolean bajo()        { return actual > 0 && actual <= minimo; }

        /** Cuánto falta para llegar al máximo (0 si ya está lleno o si no hay máximo definido). */
        public int faltante() {
            return maximo > 0 ? Math.max(0, maximo - actual) : 0;
        }
    }
}
