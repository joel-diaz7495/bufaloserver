package com.example.bufaloserver.db.entities;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Un dispositivo (celular/tablet) autorizado a loguearse contra este
 * servidor. Modelo elegido: 1 dispositivo = 1 empleado fijo — el
 * deviceToken queda atado a un único usuarioId, no a un rol compartido.
 *
 * NO usar el Android ID del teléfono como identificador de seguridad (puede
 * repetirse en emuladores, resetearse, o falsificarse). El deviceToken es
 * un secreto random generado por ESTE servidor en el momento del
 * emparejamiento (ver TokenEmparejamiento / ServidorHttp.manejarEmparejar),
 * y es lo único que el cliente guarda y manda de ahí en más.
 *
 * "Revocar" = estado -> REVOCADO, nunca se borra el registro (auditoría:
 * queda constancia de qué dispositivo entró como quién, aunque después se
 * le haya quitado el acceso).
 */
@Entity(tableName = "dispositivos", indices = { @Index(value = "deviceToken", unique = true) })
public class Dispositivo {

    public static final String AUTORIZADO = "autorizado";
    public static final String REVOCADO   = "revocado";

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Secreto largo generado al emparejar. Es lo que identifica al dispositivo
     *  en cada login — NUNCA se vuelve a mostrar una vez emparejado. */
    @ColumnInfo(name = "deviceToken")
    public String deviceToken;

    /** A qué empleado pertenece este dispositivo (fijo — 1 dispositivo = 1 persona). */
    @ColumnInfo(name = "usuarioId")
    public long usuarioId;

    /** Nombre descriptivo para la lista del admin, ej. "Celular de Juan". */
    @NonNull
    @ColumnInfo(name = "nombre", defaultValue = "''")
    public String nombre = "";

    /** autorizado | revocado. Revocar corta el acceso en el próximo request,
     *  aunque el PIN siga siendo correcto. */
    @NonNull
    @ColumnInfo(name = "estado", defaultValue = "'autorizado'")
    public String estado = AUTORIZADO;

    @ColumnInfo(name = "creado_en", defaultValue = "0")
    public long creadoEn = System.currentTimeMillis();

    @ColumnInfo(name = "ultimo_uso_en", defaultValue = "0")
    public long ultimoUsoEn = 0;

    public Dispositivo() {}

    @Ignore
    public Dispositivo(String deviceToken, long usuarioId, String nombre) {
        this.deviceToken = deviceToken;
        this.usuarioId   = usuarioId;
        this.nombre       = nombre;
        this.estado       = AUTORIZADO;
        this.creadoEn     = System.currentTimeMillis();
    }

    public boolean estaAutorizado() { return AUTORIZADO.equals(estado); }
}