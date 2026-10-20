package com.example.bufaloserver.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Código de emparejamiento de UN SOLO USO, con vencimiento corto — es lo que
 * codifica el QR que el admin genera desde "Gestión de usuarios" para dar de
 * alta un dispositivo nuevo.
 *
 * A propósito NO es el deviceToken final del dispositivo: si el QR fuera el
 * secreto permanente, una foto de pantalla del QR serviría para emparejar
 * infinitos dispositivos en cualquier momento futuro — justo el problema que
 * se quiere evitar. Este token se consume una vez (usado=true) y vence a los
 * pocos minutos (EXPIRA_MS); después de eso, aunque alguien tenga la foto,
 * /emparejar lo rechaza y no pasa nada.
 */
@Entity(tableName = "tokens_emparejamiento", indices = { @Index(value = "token", unique = true) })
public class TokenEmparejamiento {

    /** Vigencia del código: 5 minutos. Tiempo de sobra para escanear, corto
     *  para que una captura de pantalla vieja no sirva para nada. */
    public static final long EXPIRA_MS = 5 * 60 * 1000L;

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "token")
    public String token;

    /** Para qué usuario quedará atado el dispositivo que escanee este código. */
    @ColumnInfo(name = "usuarioId")
    public long usuarioId;

    @ColumnInfo(name = "creado_en", defaultValue = "0")
    public long creadoEn = System.currentTimeMillis();

    @ColumnInfo(name = "expira_en", defaultValue = "0")
    public long expiraEn = System.currentTimeMillis() + EXPIRA_MS;

    @ColumnInfo(name = "usado", defaultValue = "0")
    public boolean usado = false;

    public TokenEmparejamiento() {}

    public boolean esValido() {
        return !usado && System.currentTimeMillis() < expiraEn;
    }
}
