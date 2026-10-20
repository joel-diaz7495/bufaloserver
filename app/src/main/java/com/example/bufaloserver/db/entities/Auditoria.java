package com.example.bufaloserver.db.entities;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Registro de auditoría: una fila por cada acción "sensible" que un usuario
 * logueado (Mesero/Cajero/Administrador) hace en el sistema — cancelar un
 * pedido, cargar/editar/eliminar un gasto, etc. Pensado para que el
 * Administrador pueda rastrear después "quién hizo qué y cuándo", no para
 * que la app tome decisiones en base a esto.
 *
 * Vive SOLO en el servidor (no se replica al cliente): es información de
 * auditoría de todo el negocio, no algo que un dispositivo individual
 * necesite tener localmente, y así evita el problema de sincronizar una
 * tabla que solo crece y nunca se edita desde el cliente.
 *
 * nombreUsuario y rol quedan "congelados" (snapshot) al momento de la
 * acción, igual criterio que nombreSnapshot en ItemPedido — si el usuario
 * se borra o cambia de rol después, la fila de auditoría sigue siendo
 * legible tal cual pasó en su momento.
 */
@Entity(tableName = "auditoria",
        indices = { @Index("creado_en"), @Index("usuario_id") })
public class Auditoria {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "usuario_id")
    public long usuarioId;

    @ColumnInfo(name = "nombre_usuario")
    public String nombreUsuario;

    /** "mesero" | "cajero" | "administrador" — ver RolUsuario. */
    @ColumnInfo(name = "rol")
    public String rol;

    /** Constante corta de qué pasó — ver Auditoria.ACCION_* más abajo. */
    @ColumnInfo(name = "accion")
    public String accion;

    /** Texto legible para mostrar en la pantalla de auditoría, ej.
     *  "Canceló el pedido #045" o "Eliminó gasto: Tomates 2kg (Bs 25)". */
    @ColumnInfo(name = "detalle")
    public String detalle;

    /** Tipo de entidad afectada — "pedido", "gasto_diario", "gasto_fijo",
     *  "pago_gasto_fijo" — para poder filtrar/agrupar más adelante.
     *  ★ @NonNull a propósito: el SQL de la migración (v6→v7) la declara
     *  "NOT NULL DEFAULT ''" — sin esta anotación, Room trataría el campo
     *  como nullable y el esquema esperado no coincidiría con el real,
     *  lo que tira una excepción al ABRIR la base después de migrar (no
     *  un error de compilación). Ver AppDatabaseServidor.MIGRATION_6_7. */
    @NonNull
    @ColumnInfo(name = "entidad_tipo", defaultValue = "''")
    public String entidadTipo = "";

    /** Id de esa entidad (0 si no aplica). */
    @ColumnInfo(name = "entidad_id", defaultValue = "0")
    public long entidadId = 0;

    @ColumnInfo(name = "creado_en")
    public long creadoEn = System.currentTimeMillis();

    // ── Constantes de acción ─────────────────────────────────────────────────
    public static final String ACCION_PEDIDO_CANCELADO      = "pedido_cancelado";
    public static final String ACCION_GASTO_CREADO           = "gasto_creado";
    public static final String ACCION_GASTO_EDITADO          = "gasto_editado";
    public static final String ACCION_GASTO_ELIMINADO        = "gasto_eliminado";
    public static final String ACCION_GASTO_FIJO_CREADO      = "gasto_fijo_creado";
    public static final String ACCION_GASTO_FIJO_EDITADO     = "gasto_fijo_editado";
    public static final String ACCION_GASTO_FIJO_ELIMINADO   = "gasto_fijo_eliminado";
    public static final String ACCION_PAGO_GASTO_FIJO_CREADO   = "pago_gasto_fijo_creado";
    public static final String ACCION_PAGO_GASTO_FIJO_EDITADO  = "pago_gasto_fijo_editado";
    public static final String ACCION_PAGO_GASTO_FIJO_ELIMINADO = "pago_gasto_fijo_eliminado";
    // ★ NUEVO — reemplazan a las 6 constantes ACCION_GASTO_FIJO_*/ACCION_PAGO_GASTO_FIJO_*
    // de arriba (quedan sin usar tras unificar gastos_fijos+gastos_fijos_pagos en gastos_generales).
    public static final String ACCION_GASTO_GENERAL_CREADO    = "gasto_general_creado";
    public static final String ACCION_GASTO_GENERAL_EDITADO   = "gasto_general_editado";
    public static final String ACCION_GASTO_GENERAL_ELIMINADO = "gasto_general_eliminado";
    public static final String ACCION_RESUMEN_DIA_IMPRESO       = "resumen_dia_impreso";
    /** ★ NUEVO — limpieza de datos antiguos (manual del admin o automática del sistema). */
    public static final String ACCION_LIMPIEZA_DATOS            = "limpieza_datos";

    public Auditoria() {}
}