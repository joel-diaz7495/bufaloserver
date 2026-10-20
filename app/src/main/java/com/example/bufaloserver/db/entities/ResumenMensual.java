package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * ★ NUEVO — Totales de UN mes ya cerrado, guardados por la limpieza de datos ANTES de borrar
 * los pedidos/gastos de ese mes (ver servicio/LimpiezaDatos). Es lo que permite seguir viendo
 * "cuánto se vendió en junio" en Reportes aunque los pedidos ya no existan.
 *
 * Los totales se calculan con la MISMA regla que el PDF mensual de ReportesActivity:
 *   - ventas / descuentos / métodos de pago: solo pedidos 'pagado' (por pagado_en);
 *   - cancelados: contados aparte, no suman a ninguna venta;
 *   - gastos fijos: solo los registrados en ESE mes/año exacto (no el "molde" recurrente
 *     de un mes anterior), igual que el cliente.
 *
 * Una fila por (anio, mes): índice único. Una vez guardada NUNCA se sobrescribe
 * (si el mes ya no tiene datos, recalcularlo daría ceros).
 */
@Entity(tableName = "resumen_mensual",
        indices = { @Index(value = { "anio", "mes" }, unique = true) })
public class ResumenMensual {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "anio")
    public int anio;

    /** 1..12 (no 0..11 como Calendar.MONTH). */
    @ColumnInfo(name = "mes")
    public int mes;

    @ColumnInfo(name = "ventas_centavos")
    public int ventasCentavos;

    @ColumnInfo(name = "pedidos_pagados")
    public int pedidosPagados;

    @ColumnInfo(name = "pedidos_cancelados")
    public int pedidosCancelados;

    @ColumnInfo(name = "descuentos_centavos")
    public int descuentosCentavos;

    @ColumnInfo(name = "efectivo_centavos")
    public int efectivoCentavos;

    @ColumnInfo(name = "tarjeta_centavos")
    public int tarjetaCentavos;

    @ColumnInfo(name = "qr_centavos")
    public int qrCentavos;

    @ColumnInfo(name = "gastos_diarios_centavos")
    public int gastosDiariosCentavos;

    @ColumnInfo(name = "gastos_fijos_centavos")
    public int gastosFijosCentavos;

    @ColumnInfo(name = "creado_en")
    public long creadoEn = System.currentTimeMillis();

    public ResumenMensual() {}

    public int getGastosTotalesCentavos() { return gastosDiariosCentavos + gastosFijosCentavos; }

    public int getUtilidadCentavos() { return ventasCentavos - getGastosTotalesCentavos(); }
}
