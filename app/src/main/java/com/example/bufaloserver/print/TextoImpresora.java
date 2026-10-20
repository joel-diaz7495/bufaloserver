package com.example.bufaloserver.print;

/**
 * Sanitiza texto en español antes de mandarlo a la impresora térmica
 * (codepage GB2312, pensado para chino: no soporta tildes/ñ y va a imprimir
 * basura o cuadros vacíos con emojis).
 *
 *  - Reemplaza vocales acentuadas y ñ/Ñ por su versión sin tilde.
 *  - Reemplaza signos de apertura ¿/¡ por su versión simple.
 *  - Elimina cualquier carácter fuera del rango ASCII imprimible restante
 *    (emojis, símbolos raros, etc.) para evitar bytes basura.
 */
public final class TextoImpresora {

    private TextoImpresora() {}

    public static String sanitizar(String input) {
        if (input == null) return "";

        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char r = reemplazoAcento(c);
            if (r != 0) {
                sb.append(r);
                continue;
            }
            // Conservar ASCII imprimible (32-126) y saltos de línea.
            if (c == '\n' || (c >= 32 && c <= 126)) {
                sb.append(c);
            }
            // Cualquier otro carácter (emojis, símbolos no soportados) se descarta.
        }
        return sb.toString();
    }

    private static char reemplazoAcento(char c) {
        switch (c) {
            case 'á': return 'a';
            case 'é': return 'e';
            case 'í': return 'i';
            case 'ó': return 'o';
            case 'ú': return 'u';
            case 'Á': return 'A';
            case 'É': return 'E';
            case 'Í': return 'I';
            case 'Ó': return 'O';
            case 'Ú': return 'U';
            case 'ñ': return 'n';
            case 'Ñ': return 'N';
            case 'ü': return 'u';
            case 'Ü': return 'U';
            case '¿': return '?';
            case '¡': return '!';
            default:  return 0;
        }
    }
}
