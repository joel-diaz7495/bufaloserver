package com.example.bufaloserver.print;

import java.util.ArrayList;
import java.util.List;

/**
 * Todo lo que {@link EscPosReporteCajaBuilder} necesita para imprimir la
 * comanda de "Reporte de Caja" (botón 🖨️ separado de "Resumen del día" en
 * Reportes / Ventas del día del cliente).
 *
 * A diferencia de {@link ResumenDiaTicketData} (que calcula UTILIDAD =
 * vendido − gastos, la ganancia neta del negocio), este ticket calcula
 * EFECTIVO EN CAJA = vendido − QR − tarjeta − gastos del día − adelantos,
 * es decir cuánto dinero físico debería haber en la caja en este momento
 * (no es ganancia, es solo para contar caja).
 */
public class ReporteCajaTicketData {

    public String fechaLabel;

    /** Nombre de quien pidió imprimir el reporte (sesión activa). */
    public String impresoPor;
    public String rolImpresor;

    public int cantOrdenes;
    public int totalVendidoCentavos;

    public int qrCentavos;
    public int tarjetaCentavos;

    /** Gastos del día que NO son adelanto (compras, insumos, etc.) — se
     *  muestran como un solo total, sin detalle línea por línea. */
    public int gastosDiaCentavos;

    /** Adelantos de sueldo — sí se detallan uno por uno, con el nombre de
     *  quién lo recibió. */
    public List<AdelantoDetalle> adelantos = new ArrayList<>();
    public int adelantosCentavos;

    /** EFECTIVO EN CAJA = totalVendido − qr − tarjeta − gastosDia − adelantos */
    public int efectivoEnCajaCentavos;

    public long fechaMillis = System.currentTimeMillis();

    public static class AdelantoDetalle {
        public String nombreEmpleado;
        public int montoCentavos;

        public AdelantoDetalle(String nombreEmpleado, int montoCentavos) {
            this.nombreEmpleado = nombreEmpleado;
            this.montoCentavos = montoCentavos;
        }
    }
}
