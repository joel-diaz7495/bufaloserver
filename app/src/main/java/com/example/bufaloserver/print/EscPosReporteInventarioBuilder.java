package com.example.bufaloserver.print;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Arma el flujo de bytes ESC/POS del ticket "Reporte de Inventario" (32 columnas,
 * 58mm, sin corte automático) — mismas convenciones que
 * {@link EscPosReporteCajaBuilder}.
 *
 * Formato:
 *   REPORTE DE INVENTARIO
 *   Fecha / hora / quién lo imprimió
 *   Resumen: cuántos sin stock / bajo / OK
 *   Por categoría, cada ítem en 2 líneas:
 *       ! Coca Cola 2L              ← "!" sin stock, "*" bajo, nada si está OK
 *         Act 3  Max 24  Pedir 21   ← Pedir = máximo − actual ("Lleno" si ya llegó al máximo)
 *   TOTAL A REPONER
 */
public class EscPosReporteInventarioBuilder {

    private static final int WIDTH = 32;

    private static final byte[] INIT        = {0x1B, 0x40};
    private static final byte[] ALIGN_LEFT   = {0x1B, 0x61, 0x00};
    private static final byte[] ALIGN_CENTER = {0x1B, 0x61, 0x01};
    private static final byte[] BOLD_ON      = {0x1B, 0x45, 0x01};
    private static final byte[] BOLD_OFF     = {0x1B, 0x45, 0x00};

    private static Charset charsetImpresora() {
        try {
            return Charset.forName("GB2312");
        } catch (Exception e) {
            return Charset.forName("US-ASCII");
        }
    }

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public static byte[] build(ReporteInventarioTicketData data) {
        EscPosReporteInventarioBuilder b = new EscPosReporteInventarioBuilder();
        return b.armar(data);
    }

    private byte[] armar(ReporteInventarioTicketData data) {
        try {
            buf.write(INIT);

            buf.write(ALIGN_CENTER);
            buf.write(BOLD_ON);
            escribirLinea("REPORTE DE INVENTARIO");
            buf.write(BOLD_OFF);
            buf.write(ALIGN_LEFT);
            escribirSeparador();

            String fecha = data.fechaLabel != null ? data.fechaLabel
                    : new SimpleDateFormat("dd/MM/yyyy", new Locale("es", "BO")).format(new Date(data.fechaMillis));
            String hora = new SimpleDateFormat("HH:mm", new Locale("es", "BO")).format(new Date(data.fechaMillis));
            escribirLinea("Fecha: " + sanitizar(fecha));
            escribirLinea("Impreso: " + hora);
            escribirLinea("Por: " + sanitizar(data.impresoPor)
                    + (data.rolImpresor != null && !data.rolImpresor.trim().isEmpty()
                        ? " (" + sanitizar(capitalizar(data.rolImpresor)) + ")" : ""));
            escribirSeparador();

            // ── Resumen ──
            escribirLinea(linea("Sin stock", String.valueOf(data.cantSinStock)));
            escribirLinea(linea("Stock bajo", String.valueOf(data.cantBajo)));
            escribirLinea(linea("OK", String.valueOf(data.cantOk)));
            escribirSeparador();
            escribirLinea("! sin stock   * stock bajo");

            // ── Detalle por categoría ──
            for (ReporteInventarioTicketData.CategoriaInv cat : data.categorias) {
                if (cat.items == null || cat.items.isEmpty()) continue;
                escribirSeparador();
                buf.write(BOLD_ON);
                escribirLinea(sanitizar(cat.nombre).toUpperCase(Locale.ROOT));
                buf.write(BOLD_OFF);

                for (ReporteInventarioTicketData.ItemInv it : cat.items) {
                    escribirItem(it);
                }
            }
            escribirSeparador();

            // ── Total a reponer ──
            buf.write(BOLD_ON);
            escribirLinea(linea("TOTAL A REPONER:", data.totalFaltante + " und"));
            buf.write(BOLD_OFF);
            if (data.cantSinMaximo > 0) {
                escribirLinea("(" + data.cantSinMaximo + " sin maximo definido: Max --)");
            }

            buf.write(new byte[]{0x1B, 0x64, 0x04}); // feed 4 líneas, sin cuchilla

        } catch (IOException e) {
            // ByteArrayOutputStream no lanza IOException en la práctica
        }
        return buf.toByteArray();
    }

    /** Un ítem en 2 líneas: nombre (con marca de estado) y "Act / Max / Pedir". */
    private void escribirItem(ReporteInventarioTicketData.ItemInv it) throws IOException {
        String marca = it.sinStock() ? "! " : it.bajo() ? "* " : "  ";

        // Nombre, partido en varias líneas si no entra. La marca solo va en la primera.
        String nombre = sanitizar(it.nombre).trim();
        if (nombre.isEmpty()) nombre = "-";
        int ancho = WIDTH - 2;
        boolean primera = true;
        while (!nombre.isEmpty()) {
            int corte = Math.min(ancho, nombre.length());
            if (corte < nombre.length()) {
                int espacio = nombre.lastIndexOf(' ', corte);
                if (espacio > 0) corte = espacio;
            }
            escribirLinea((primera ? marca : "  ") + nombre.substring(0, corte).trim());
            nombre = nombre.substring(corte).trim();
            primera = false;
        }

        String max = it.tieneMaximo() ? String.valueOf(it.maximo) : "--";
        String pedir;
        if (!it.tieneMaximo())        pedir = "Pedir --";
        else if (it.faltante() == 0)  pedir = "Lleno";
        else                          pedir = "Pedir " + it.faltante();

        String detalle = "  Act " + it.actual + "  Max " + max + "  " + pedir;
        if (detalle.length() > WIDTH) {
            detalle = "Act" + it.actual + " Max" + max + " " + pedir; // versión compacta (números muy grandes)
        }
        escribirLinea(detalle);
    }

    private static String capitalizar(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String repetir(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** Línea con texto a la izquierda y valor alineado a la derecha, total WIDTH chars. */
    private static String linea(String izq, String der) {
        if (izq == null) izq = "";
        if (der == null) der = "";
        int espacio = WIDTH - izq.length() - der.length();
        if (espacio < 1) {
            int maxIzq = Math.max(0, WIDTH - der.length() - 1);
            izq = izq.length() > maxIzq ? izq.substring(0, maxIzq) : izq;
            espacio = WIDTH - izq.length() - der.length();
            if (espacio < 1) espacio = 1;
        }
        StringBuilder sb = new StringBuilder(izq);
        for (int i = 0; i < espacio; i++) sb.append(' ');
        sb.append(der);
        return sb.toString();
    }

    private void escribirSeparador() throws IOException {
        escribirLinea(repetir('-', WIDTH));
    }

    private void escribirLinea(String texto) throws IOException {
        byte[] bytes = texto.getBytes(charsetImpresora());
        buf.write(bytes);
        buf.write('\n');
    }

    private static String sanitizar(String s) {
        return TextoImpresora.sanitizar(s);
    }
}
