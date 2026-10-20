package com.example.bufaloserver.print;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Convierte el flujo de bytes ESC/POS de un ticket (el MISMO que se manda a la
 * impresora) en una lista de líneas de texto con su formato, para mostrarlo en
 * pantalla como previsualización antes de imprimir.
 *
 * Se parte de los bytes finales en vez de armar un segundo formato a mano a
 * propósito: así lo que se ve en la previsualización es exactamente lo que
 * salen los builders (EscPosResumenDiaBuilder, EscPosReporteCajaBuilder,
 * EscPosReporteInventarioBuilder), y si alguien cambia un builder la vista
 * previa cambia sola, sin que haya dos versiones que mantener.
 *
 * Solo entiende los comandos que usan esos builders:
 *   ESC @        inicializar
 *   ESC a n      alineación (0 izquierda, 1 centro)
 *   ESC E n      negrita
 *   GS ! n       tamaño (distinto de 0 = doble)
 *   ESC d n      avance de papel (se ignora)
 * Cualquier otro comando ESC/GS se salta sin romper nada.
 *
 * Cada línea sale como {"t": texto, "a": 0|1, "b": 0|1, "d": 0|1}
 * (alineación centrada, negrita, tamaño doble).
 */
public final class EscPosPreview {

    private EscPosPreview() {}

    public static JSONArray aLineas(byte[] datos) {
        JSONArray salida = new JSONArray();
        if (datos == null) return salida;

        boolean centro = false, negrita = false, doble = false;
        StringBuilder linea = new StringBuilder();
        int i = 0;
        final int n = datos.length;

        while (i < n) {
            int c = datos[i] & 0xFF;

            if (c == 0x1B) { // ESC
                if (i + 1 >= n) break;
                int cmd = datos[i + 1] & 0xFF;
                switch (cmd) {
                    case 0x40: // ESC @
                        centro = false; negrita = false; doble = false;
                        i += 2;
                        break;
                    case 0x61: // ESC a n
                        if (i + 2 >= n) { i = n; break; }
                        centro = (datos[i + 2] & 0x03) == 1;
                        i += 3;
                        break;
                    case 0x45: // ESC E n
                        if (i + 2 >= n) { i = n; break; }
                        negrita = (datos[i + 2] & 0x01) != 0;
                        i += 3;
                        break;
                    case 0x64: // ESC d n (feed)
                        i += 3;
                        break;
                    default:
                        i += 2;
                        break;
                }
            } else if (c == 0x1D) { // GS
                if (i + 1 >= n) break;
                int cmd = datos[i + 1] & 0xFF;
                if (cmd == 0x21) { // GS ! n
                    if (i + 2 >= n) { i = n; break; }
                    doble = (datos[i + 2] & 0xFF) != 0;
                    i += 3;
                } else {
                    i += 2;
                }
            } else if (c == '\n') {
                agregar(salida, linea.toString(), centro, negrita, doble);
                linea.setLength(0);
                i++;
            } else {
                // Los builders mandan solo ASCII imprimible (ver TextoImpresora).
                if (c >= 32 && c <= 126) linea.append((char) c);
                i++;
            }
        }
        if (linea.length() > 0) agregar(salida, linea.toString(), centro, negrita, doble);
        return salida;
    }

    private static void agregar(JSONArray salida, String texto, boolean centro, boolean negrita, boolean doble) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", texto);
            o.put("a", centro ? 1 : 0);
            o.put("b", negrita ? 1 : 0);
            o.put("d", doble ? 1 : 0);
            salida.put(o);
        } catch (JSONException ignored) {
            // put() con valores simples no falla en la práctica.
        }
    }
}
