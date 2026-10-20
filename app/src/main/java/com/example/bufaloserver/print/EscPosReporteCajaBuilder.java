package com.example.bufaloserver.print;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Arma el flujo de bytes ESC/POS de la comanda "Reporte de Caja" (32
 * columnas, 58mm, sin QR de impresora, sin corte automático) — mismas
 * convenciones que {@link EscPosResumenDiaBuilder}, pero autocontenido:
 * este ticket es el cierre de CAJA (cuánto efectivo físico debe haber),
 * no el cierre de UTILIDAD del negocio.
 *
 * Formato (aprobado):
 *   TOTAL VENDIDO
 *   (-) QR
 *   (-) Tarjeta
 *   (-) Gastos del dia   ← un solo total, sin detalle
 *   (-) Adelantos        ← un solo total arriba...
 *   ------------------------------
 *   ADELANTOS             ← ...y acá SÍ detallado, con nombre de quién lo recibió
 *     Nombre         monto
 *   ------------------------------
 *   EFECTIVO EN CAJA
 */
public class EscPosReporteCajaBuilder {

    private static final int WIDTH = 32;

    private static final byte[] INIT        = {0x1B, 0x40};
    private static final byte[] ALIGN_LEFT   = {0x1B, 0x61, 0x00};
    private static final byte[] ALIGN_CENTER = {0x1B, 0x61, 0x01};
    private static final byte[] BOLD_ON      = {0x1B, 0x45, 0x01};
    private static final byte[] BOLD_OFF     = {0x1B, 0x45, 0x00};
    private static final byte[] SIZE_DOUBLE  = {0x1D, 0x21, 0x11};
    private static final byte[] SIZE_NORMAL  = {0x1D, 0x21, 0x00};

    private static Charset charsetImpresora() {
        try {
            return Charset.forName("GB2312");
        } catch (Exception e) {
            return Charset.forName("US-ASCII");
        }
    }

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public static byte[] build(ReporteCajaTicketData data) {
        EscPosReporteCajaBuilder b = new EscPosReporteCajaBuilder();
        return b.armar(data);
    }

    private byte[] armar(ReporteCajaTicketData data) {
        try {
            buf.write(INIT);

            // ── Cabecera ─────────────────────────────────────────────────────
            buf.write(ALIGN_CENTER);
            buf.write(BOLD_ON);
            escribirLinea("REPORTE DE CAJA");
            buf.write(BOLD_OFF);
            buf.write(ALIGN_LEFT);
            escribirSeparador();

            String fecha = data.fechaLabel != null ? data.fechaLabel
                    : new SimpleDateFormat("dd/MM/yyyy", new Locale("es", "BO")).format(new Date(data.fechaMillis));
            String hora = new SimpleDateFormat("HH:mm", new Locale("es", "BO")).format(new Date(data.fechaMillis));
            escribirLinea("Fecha: " + sanitizar(fecha));
            escribirLinea(linea("Impreso: " + hora, ""));
            escribirLinea("Por: " + sanitizar(data.impresoPor)
                    + (data.rolImpresor != null && !data.rolImpresor.trim().isEmpty()
                        ? " (" + sanitizar(capitalizar(data.rolImpresor)) + ")" : ""));
            escribirSeparador();

            // ── Ventas ───────────────────────────────────────────────────────
            escribirLinea(linea("Ordenes cobradas:", String.valueOf(data.cantOrdenes)));
            escribirSeparador();

            buf.write(BOLD_ON);
            buf.write(SIZE_DOUBLE);
            escribirLinea(linea("VENDIDO:", formatBs(data.totalVendidoCentavos / 100.0)));
            buf.write(SIZE_NORMAL);
            buf.write(BOLD_OFF);
            escribirSeparador();

            // ── Restas (todo lo que NO es efectivo físico) ─────────────────────
            escribirLinea(linea("QR", "-" + formatBs(data.qrCentavos / 100.0)));
            escribirLinea(linea("Tarjeta", "-" + formatBs(data.tarjetaCentavos / 100.0)));
            escribirLinea(linea("Gastos del dia", "-" + formatBs(data.gastosDiaCentavos / 100.0)));
            escribirLinea(linea("Adelantos", "-" + formatBs(data.adelantosCentavos / 100.0)));
            escribirSeparador();

            // ── Detalle de adelantos (con nombre de quién lo recibió) ───────────
            buf.write(BOLD_ON);
            escribirLinea("ADELANTOS");
            buf.write(BOLD_OFF);
            if (data.adelantos == null || data.adelantos.isEmpty()) {
                escribirLinea("  (sin adelantos hoy)");
            } else {
                for (ReporteCajaTicketData.AdelantoDetalle a : data.adelantos) {
                    escribirLinea(linea("  " + sanitizar(a.nombreEmpleado), formatBs(a.montoCentavos / 100.0)));
                }
            }
            escribirSeparador();

            // ── Resultado: efectivo físico que debe haber en caja ───────────────
            buf.write(BOLD_ON);
            buf.write(SIZE_DOUBLE);
            escribirLinea(linea("EFECTIVO EN CAJA:", formatBs(data.efectivoEnCajaCentavos / 100.0)));
            buf.write(SIZE_NORMAL);
            buf.write(BOLD_OFF);

            buf.write(new byte[]{0x1B, 0x64, 0x04}); // feed 4 líneas, sin cuchilla

        } catch (IOException e) {
            // ByteArrayOutputStream no lanza IOException real.
        }
        return buf.toByteArray();
    }

    private static String capitalizar(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String formatBs(double v) {
        long centavos = Math.round(v * 100);
        if (centavos % 100 == 0) return "Bs" + (centavos / 100);
        return String.format(Locale.US, "Bs%.2f", centavos / 100.0);
    }

    private static String repetir(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** Línea con texto a la izquierda y (si cabe) un valor alineado a la derecha, total WIDTH chars. */
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
