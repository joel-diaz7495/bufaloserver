package com.example.bufaloserver.print;

import com.example.bufaloserver.db.entities.ItemModificadorSeleccionado;
import com.example.bufaloserver.db.entities.ItemPedido;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Arma el flujo de bytes ESC/POS del ticket de cocina a partir de un
 * {@link TicketData}, del lado del SERVIDOR. Stateless: cada llamada a
 * build() es independiente.
 *
 * Mismo formato visual que com.example.myapplication.print.EscPosBuilder
 * (cliente) — ancho 32 caracteres (58mm), sin QR, sin corte automático,
 * etiquetas "PARA LLEVAR"/"PARA SERVIR AQUI"/"PENDIENTE DE PAGO" en modo
 * invertido (GS B).
 *
 * ★ CAMBIO — a diferencia de OrderItem (cliente), acá no existe el concepto
 * de "sub-órdenes" dentro de un mismo ítem: cada ItemPedido es una fila
 * plana (nombreSnapshot + cantidad + salsas/modificadoresConPrecio, ver
 * ItemPedido.java). Cuando el carrito tiene el mismo producto con distinta
 * configuración (ej. 1 Alitas con salsa + 1 Alitas sin nada), eso llega acá
 * como DOS filas de ItemPedido con el mismo nombreSnapshot — el equivalente
 * a lo que en el cliente eran sub-órdenes dentro de un solo OrderItem. Por
 * eso, en vez de agrupar sub-órdenes de un item, acá se agrupan FILAS que
 * comparten producto (mismo nombreSnapshot + precioCentavosSnapshot), con
 * la misma lógica de "Nx <config>" ya usada en el cliente — ver
 * agruparPorProducto / agruparPorConfiguracion más abajo.
 */
public class EscPosBuilder {

    private static final int WIDTH = 32;

    private static final byte[] INIT          = {0x1B, 0x40};
    private static final byte[] ALIGN_LEFT     = {0x1B, 0x61, 0x00};
    private static final byte[] ALIGN_CENTER   = {0x1B, 0x61, 0x01};
    private static final byte[] BOLD_ON        = {0x1B, 0x45, 0x01};
    private static final byte[] BOLD_OFF       = {0x1B, 0x45, 0x00};
    private static final byte[] SIZE_DOUBLE    = {0x1D, 0x21, 0x11};
    private static final byte[] SIZE_NORMAL    = {0x1D, 0x21, 0x00};

    /** GS B 1 — impresión invertida (blanco sobre negro) ON/OFF. */
    private static final byte[] REVERSE_ON     = {0x1D, 0x42, 0x01};
    private static final byte[] REVERSE_OFF    = {0x1D, 0x42, 0x00};

    private static Charset charsetImpresora() {
        try {
            return Charset.forName("GB2312");
        } catch (Exception e) {
            return Charset.forName("US-ASCII");
        }
    }

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public static byte[] build(TicketData data) {
        EscPosBuilder b = new EscPosBuilder();
        return b.armar(data);
    }

    private byte[] armar(TicketData data) {
        try {
            buf.write(INIT);

            // ── Cabecera (compacta, sin nombre del negocio) ─────────────────
            String fecha = new SimpleDateFormat("dd/MM/yyyy", new Locale("es", "BO")).format(new Date(data.fechaMillis));
            String hora  = new SimpleDateFormat("HH:mm", new Locale("es", "BO")).format(new Date(data.fechaMillis));
            escribirLinea(linea("Orden #" + String.format("%03d", data.numeroOrden), fecha));
            escribirLinea(linea("Cliente: " + sanitizar(data.cliente), hora));
            // ★ NUEVO — quién vendió/tomó este pedido, para poder rastrear un
            // ticket físico hasta el mesero/cajero que lo generó.
            if (data.vendedor != null && !data.vendedor.trim().isEmpty()) {
                escribirLinea("Vendio: " + sanitizar(data.vendedor.trim()));
            }
            // ★ NUEVO — si lo cobró alguien distinto de quien lo tomó (mesero
            // toma, cajero cobra), queda esa segunda línea. TicketData ya
            // viene con cajero=null cuando no aplica (ver ServidorHttp), así
            // que acá no hace falta comparar contra "vendedor" de nuevo.
            if (data.cajero != null && !data.cajero.trim().isEmpty()) {
                escribirLinea("Cobro: " + sanitizar(data.cajero.trim()));
            }

            // ── ★ NUEVO — cartel de cancelación ──────────────────────────────
            // Va ANTES de la lista de items a propósito: es la comanda de
            // aviso que se manda a cocina cuando ya se había impreso el
            // pedido original y hay que frenar la preparación — necesita ser
            // lo primero que se lea al arrancar el papel, no algo que
            // aparezca recién después de volver a ver todos los items.
            if ("cancelado".equals(data.estado)) {
                escribirSeparador();
                buf.write(SIZE_DOUBLE);
                if (data.canceladoEstabaPagado) {
                    // ★ NUEVO — este pedido ya se había cobrado. Es un aviso
                    // distinto al de un pendiente: cocina no solo tiene que
                    // frenar, alguien tiene que ir a caja a resolver la
                    // devolución — por eso el texto explícito "REVISAR CAJA"
                    // en vez del simple "CANCELADO".
                    escribirEtiquetaInvertida("YA COBRADO");
                    escribirEtiquetaInvertida("REVISAR CAJA");
                } else {
                    escribirEtiquetaInvertida("CANCELADO");
                }
                buf.write(SIZE_NORMAL);
                if (data.canceladoPor != null && !data.canceladoPor.trim().isEmpty()) {
                    escribirLinea("Cancelo: " + sanitizar(data.canceladoPor.trim()));
                }
                escribirSeparador();
            }

            // ── Modo aqui/llevar (simple o mixto) — invertido, 1 sola línea ─
            boolean hayAqui = false, hayLlevar = false;
            for (ItemPedido it : data.items) {
                if (it.esLlevar) hayLlevar = true; else hayAqui = true;
            }
            boolean mixto = hayAqui && hayLlevar;

            if (!mixto) {
                escribirEtiquetaInvertida(hayLlevar ? "PARA LLEVAR" : "PARA SERVIR AQUI");
            }

            escribirSeparador();

            if (mixto) {
                List<ItemPedido> aqui = new ArrayList<>();
                List<ItemPedido> llevar = new ArrayList<>();
                for (ItemPedido it : data.items) (it.esLlevar ? llevar : aqui).add(it);

                if (!aqui.isEmpty()) {
                    escribirEtiquetaInvertida("PARA SERVIR AQUI");
                    escribirItems(aqui);
                }
                if (!llevar.isEmpty()) {
                    escribirEtiquetaInvertida("PARA LLEVAR");
                    escribirItems(llevar);
                }
            } else {
                escribirItems(data.items);
            }

            escribirSeparador();

            // ── Totales ──────────────────────────────────────────────────────
            escribirLinea(linea("Subtotal:", formatBs(data.subtotal)));
            if (data.descuento > 0) {
                escribirLinea(linea("Descuento:", "-" + formatBs(data.descuento)));
            }
            buf.write(BOLD_ON);
            buf.write(SIZE_DOUBLE);
            escribirLinea(linea("TOTAL:", formatBs(data.total)));
            buf.write(SIZE_NORMAL);
            buf.write(BOLD_OFF);

            if ("pendiente".equals(data.estado)) {
                escribirEtiquetaInvertida("PENDIENTE DE PAGO");
            } else if ("pagado".equals(data.estado)) {
                escribirLinea("Pago: " + etiquetaPago(data));
            }

            // Avance final de papel (sin cuchilla automática)
            buf.write(new byte[]{0x1B, 0x64, 0x04}); // feed 4 líneas

        } catch (IOException e) {
            // ByteArrayOutputStream no lanza IOException real, pero por firma del API.
        }
        return buf.toByteArray();
    }

    /**
     * Escribe una etiqueta corta centrada, en modo invertido (blanco sobre
     * negro), ocupando una sola línea de papel.
     */
    private void escribirEtiquetaInvertida(String texto) throws IOException {
        buf.write(ALIGN_CENTER);
        buf.write(REVERSE_ON);
        buf.write(BOLD_ON);
        escribirLinea(" " + sanitizar(texto) + " ");
        buf.write(BOLD_OFF);
        buf.write(REVERSE_OFF);
        buf.write(ALIGN_LEFT);
    }

    // ── Por producto (agrupando filas de ItemPedido) ────────────────────────

    /**
     * Agrupa las filas por producto (mismo nombreSnapshot + precio) y
     * escribe un bloque por cada uno, preservando el orden de la primera
     * aparición — igual que hacía antes el loop plano fila por fila, pero
     * ahora colapsando filas repetidas del mismo producto en un solo bloque.
     */
    private void escribirItems(List<ItemPedido> items) throws IOException {
        Map<String, List<ItemPedido>> porProducto = new LinkedHashMap<>();
        for (ItemPedido ip : items) {
            String clave = (ip.nombreSnapshot == null ? "" : ip.nombreSnapshot) + "|" + ip.precioCentavosSnapshot;
            porProducto.computeIfAbsent(clave, k -> new ArrayList<>()).add(ip);
        }
        for (List<ItemPedido> filas : porProducto.values()) {
            escribirGrupoProducto(filas);
        }
    }

    private void escribirGrupoProducto(List<ItemPedido> filas) throws IOException {
        String nombre = sanitizar(filas.get(0).nombreSnapshot != null ? filas.get(0).nombreSnapshot : "");

        int cantidadTotal = 0;
        long baseCentavosTotal = 0;
        for (ItemPedido ip : filas) {
            cantidadTotal += ip.cantidad;
            baseCentavosTotal += (long) ip.precioCentavosSnapshot * ip.cantidad;
        }
        escribirLinea(linea(cantidadTotal + "x " + nombre, formatBs(baseCentavosTotal / 100.0)));

        if (filas.size() == 1) {
            // Caso simple: un solo producto, ni siquiera hace falta agrupar
            // por configuración — se imprime tal cual, como antes.
            escribirConfiguracion(filas.get(0), filas.get(0).cantidad, "  ");
            return;
        }

        // Varias filas del mismo producto — ¿todas con exactamente la misma
        // configuración (bañado/salsas/mods/nota)? Si sí, no hace falta
        // desglosar por sub-línea: se imprime una vez, multiplicando los
        // extras por la cantidad total (mismo criterio que en el cliente).
        boolean todasIdenticas = true;
        String firmaRef = firma(filas.get(0));
        for (ItemPedido ip : filas) {
            if (!firma(ip).equals(firmaRef)) { todasIdenticas = false; break; }
        }

        if (todasIdenticas) {
            escribirConfiguracion(filas.get(0), cantidadTotal, "  ");
            return;
        }

        // ★ Configuraciones distintas — agrupar por firma y mostrar
        // "  Nx <config>" una vez por configuración distinta, en vez de una
        // línea por unidad. Ver el mismo patrón en el EscPosBuilder cliente.
        Map<String, List<ItemPedido>> porFirma = new LinkedHashMap<>();
        for (ItemPedido ip : filas) {
            porFirma.computeIfAbsent(firma(ip), k -> new ArrayList<>()).add(ip);
        }
        for (List<ItemPedido> grupo : porFirma.values()) {
            int cantidadGrupo = 0;
            for (ItemPedido ip : grupo) cantidadGrupo += ip.cantidad;
            escribirConfiguracion(grupo.get(0), cantidadGrupo, "     ");
        }
    }

    /**
     * Escribe la configuración (bañado/salsas/modificadores/nota) de un
     * representante, con la cantidad ya resuelta (puede ser la de una sola
     * fila, o la suma de varias filas idénticas/agrupadas).
     *
     * indent es "  " para la línea "Nx ..." de nivel producto (caso simple o
     * todasIdenticas) y "     " para las sub-líneas dentro de un producto
     * con configuraciones mixtas — mismos anchos que usa el cliente.
     */
    private void escribirConfiguracion(ItemPedido rep, int cantidad, String indent) throws IOException {
        boolean esSubLinea = indent.length() > 2;
        boolean tieneSalsas = rep.salsas != null && !rep.salsas.isEmpty();
        boolean tieneMods = rep.modificadoresConPrecio != null && !rep.modificadoresConPrecio.isEmpty();
        boolean tieneNota = rep.notaCocina != null && !rep.notaCocina.trim().isEmpty();

        if (esSubLinea) {
            // Sub-línea "  Nx ..." dentro de un producto con configuraciones
            // mixtas — mismo formato acordado para el cliente.
            StringBuilder cabecera = new StringBuilder("  " + cantidad + "x");
            if (rep.banado) cabecera.append(" Banado");
            if (tieneSalsas) {
                cabecera.append(" S: ").append(sanitizar(joinSalsas(rep.salsas)));
            } else if (!rep.banado && !tieneMods) {
                cabecera.append(" Sin extras");
            }
            escribirLinea(cabecera.toString());
        } else {
            if (rep.banado) escribirLinea("  Banado");
            if (tieneSalsas) escribirLinea("  S: " + sanitizar(joinSalsas(rep.salsas)));
        }

        if (tieneMods) {
            escribirExtras(rep.modificadoresConPrecio, cantidad, indent);
        }
        if (tieneNota) {
            escribirLinea(indent + "O: " + sanitizar(rep.notaCocina.trim()));
        }
    }

    /**
     * Mismo criterio que escribirExtras del cliente: los modificadores con
     * costo (precioExtraCentavos > 0) van uno por línea, alineados como una
     * suma y multiplicados por la cantidad; los sin costo se agrupan en una
     * sola línea "M: ...".
     */
    private void escribirExtras(List<ItemModificadorSeleccionado> extras, int cantidad, String indent) throws IOException {
        List<String> sinCosto = new ArrayList<>();
        for (ItemModificadorSeleccionado m : extras) {
            if (m == null || m.nombre == null) continue;
            if (m.precioExtraCentavos > 0) {
                long totalCentavos = (long) m.precioExtraCentavos * Math.max(1, cantidad);
                escribirLinea(linea(indent + "+ " + sanitizar(m.nombre), formatBs(totalCentavos / 100.0)));
            } else {
                sinCosto.add(m.nombre);
            }
        }
        if (!sinCosto.isEmpty()) {
            escribirLinea(indent + "M: " + sanitizar(join(sinCosto)));
        }
    }

    /** Firma de configuración: bañado + salsas + modificadores + nota. */
    private static String firma(ItemPedido ip) {
        return (ip.banado ? "B1" : "B0")
                + "|" + join(ip.salsas)
                + "|" + firmaMods(ip.modificadoresConPrecio)
                + "|" + (ip.notaCocina == null ? "" : ip.notaCocina.trim());
    }

    private static String firmaMods(List<ItemModificadorSeleccionado> mods) {
        if (mods == null || mods.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ItemModificadorSeleccionado m : mods) {
            if (m == null) continue;
            sb.append(m.nombre).append(':').append(m.precioExtraCentavos).append(';');
        }
        return sb.toString();
    }

    // ── Helpers de formato ───────────────────────────────────────────────────

    private String etiquetaPago(TicketData data) {
        if ("mixto".equals(data.metodoPago)) {
            List<String> partes = new ArrayList<>();
            if (data.mixtoEfectivo > 0) partes.add("Efec " + formatBs(data.mixtoEfectivo));
            if (data.mixtoTarjeta > 0) partes.add("Tarj " + formatBs(data.mixtoTarjeta));
            if (data.mixtoQr > 0) partes.add("QR " + formatBs(data.mixtoQr));
            return "Mixto (" + join(partes) + ")";
        }
        if (data.metodoPago == null) return "";
        switch (data.metodoPago) {
            case "efectivo": return "Efectivo";
            case "tarjeta":  return "Tarjeta";
            case "qr":       return "QR";
            default:         return data.metodoPago;
        }
    }

    private static String join(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        return String.join(", ", items);
    }

    /**
     * ★ FIX — igual criterio que OrderItem.getSalsasText() del cliente:
     * agrupa salsas repetidas y las antepone con "Nx " en vez de listarlas
     * una por una. Antes, 5 unidades de "Brava" imprimían literalmente
     * "Brava, Brava, Brava, Brava, Brava" (join() no agrupaba nada); ahora
     * imprime "5x Brava".
     */
    private static String joinSalsas(List<String> salsas) {
        if (salsas == null || salsas.isEmpty()) return "";
        java.util.LinkedHashMap<String, Integer> conteo = new java.util.LinkedHashMap<>();
        for (String s : salsas) {
            if (s == null) continue;
            conteo.merge(s, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        boolean primero = true;
        for (java.util.Map.Entry<String, Integer> e : conteo.entrySet()) {
            if (!primero) sb.append(", ");
            if (e.getValue() > 1) sb.append(e.getValue()).append("x ");
            sb.append(e.getKey());
            primero = false;
        }
        return sb.toString();
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