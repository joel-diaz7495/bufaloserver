package com.example.bufaloserver.print;

import com.example.bufaloserver.db.entities.GastoDiario;

import java.util.ArrayList;
import java.util.List;

/**
 * Todo lo que {@link EscPosResumenDiaBuilder} necesita para imprimir la
 * comanda de "Resumen del día" (botón 🖨️ en Reportes / Ventas del día del
 * cliente) — versión SERVIDOR.
 *
 * A propósito NO reutiliza {@link TicketData} (esa clase está pensada para
 * el ticket de UN pedido — items de cocina, para llevar/aquí, etc.) — este
 * es un ticket distinto: el cierre/resumen del día completo (ventas por
 * método de pago + gastos detallados + utilidad), mismo contenido que
 * imprime ReportesActivity en el PDF del "Reporte del día", pero en formato
 * comanda de 32 columnas.
 */
public class ResumenDiaTicketData {

    public String fechaLabel;

    /** Nombre de quien pidió imprimir el resumen (sesión activa en modo
     *  Conectado). Nunca null/vacío en este flujo — se resuelve en
     *  ServidorHttp.imprimirResumenDia() antes de armar el ticket. */
    public String impresoPor;
    public String rolImpresor;

    public int cantOrdenes;
    public int totalVendidoCentavos;
    public int descuentosCentavos;

    public int efectivoCentavos;
    public int tarjetaCentavos;
    public int qrCentavos;

    public List<GastoDiario> gastos = new ArrayList<>();
    public int totalGastosCentavos;

    public int utilidadCentavos;

    public long fechaMillis = System.currentTimeMillis();
}
