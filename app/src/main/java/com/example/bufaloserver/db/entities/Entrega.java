package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;


/**
 * Registro de entrega de un pedido.
 * Se crea cuando el pedido pasa a estado "pendiente" (enviado a cocina).
 * Se actualiza cuando se cobra y entrega al cliente.
 *
 * ★ NOTA — a día de hoy NINGÚN endpoint de ServidorHttp usa esta entidad ni
 * EntregaDao (se verificó con grep sobre todo el módulo servidor y el
 * cliente): la tabla "entregas" existe y Room la mantiene al día en cada
 * migración, pero nada la lee ni la escribe. Probablemente quedó de una
 * función pensada para modo Independiente que nunca se conectó del lado
 * servidor. No se borra la entidad/tabla acá porque sacarla de verdad
 * requeriría además una migración que haga DROP TABLE — demasiado para una
 * limpieza sin ningún beneficio funcional. Si en algún momento se decide
 * usarla o borrarla de una vez, este es el lugar para empezar a mirar.
 */
@Entity(
        tableName = "entregas",
        foreignKeys = @ForeignKey(
                entity    = com.example.bufaloserver.db.entities.Pedido.class,
                parentColumns = "id",
                childColumns  = "pedido_id",
                onDelete  = ForeignKey.CASCADE
        ),
        indices = { @Index(value = "pedido_id", unique = true) }
)
public class Entrega {

    public static final String ESTADO_PENDIENTE  = "pendiente";
    public static final String ESTADO_ENTREGADO  = "entregado";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "pedido_id")
    public long pedidoId;

    /** "pendiente" | "entregado" */
    @ColumnInfo(name = "estado")
    public String estado = ESTADO_PENDIENTE;

    /** Método de pago con el que se cobró finalmente (puede diferir del pedido) */
    @ColumnInfo(name = "metodo_pago_final")
    public String metodoPagoFinal = null;

    @ColumnInfo(name = "entregado_en")
    public Long entregadoEn = null;

    // ── Constructor ──────────────────────────────────────────────────────────

    public Entrega() {}
    @Ignore
    public Entrega(long pedidoId) {
        this.pedidoId = pedidoId;
        this.estado   = ESTADO_PENDIENTE;
    }
}