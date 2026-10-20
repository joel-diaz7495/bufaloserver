package com.example.bufaloserver.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.bufaloserver.db.entities.ResumenMensual;

import java.util.List;

/** ★ NUEVO — ver ResumenMensual. */
@Dao
public interface ResumenMensualDao {

    /** IGNORE a propósito: un resumen ya guardado nunca se pisa (el mes puede ya no tener datos). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertarSiNoExiste(ResumenMensual resumen);

    @Query("SELECT * FROM resumen_mensual ORDER BY anio DESC, mes DESC")
    List<ResumenMensual> getTodosSync();

    @Query("SELECT * FROM resumen_mensual WHERE anio = :anio AND mes = :mes LIMIT 1")
    ResumenMensual getSync(int anio, int mes);

    @Query("SELECT COUNT(*) FROM resumen_mensual")
    int contarSync();

    /** Mes más antiguo con resumen, como anio*100+mes (ej. 202606), o null si no hay ninguno. */
    @Query("SELECT MIN(anio * 100 + mes) FROM resumen_mensual")
    Integer getMesMasAntiguoSync();

    // ══════════════════════════════════════════════════════════════════════
    // Totales de un mes. Mismas reglas que ReportesActivity (y que EstadisticasDao, que ya
    // no está registrada en la base): solo pedidos 'pagado' por pagado_en; cancelados aparte.
    // ══════════════════════════════════════════════════════════════════════

    @Query("SELECT COALESCE(SUM(total_centavos - descuento_centavos), 0) " +
            "FROM pedidos WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int calcVentasCentavos(long inicio, long fin);

    @Query("SELECT COUNT(*) FROM pedidos " +
            "WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int calcPedidosPagados(long inicio, long fin);

    @Query("SELECT COUNT(*) FROM pedidos " +
            "WHERE estado = 'cancelado' AND cancelado_en BETWEEN :inicio AND :fin")
    int calcPedidosCancelados(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(descuento_centavos), 0) FROM pedidos " +
            "WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int calcDescuentosCentavos(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(CASE " +
            "WHEN metodo_pago = 'efectivo' THEN total_centavos - descuento_centavos " +
            "WHEN metodo_pago = 'mixto' THEN mixto_efectivo_centavos ELSE 0 END), 0) " +
            "FROM pedidos WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int calcEfectivoCentavos(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(CASE " +
            "WHEN metodo_pago = 'tarjeta' THEN total_centavos - descuento_centavos " +
            "WHEN metodo_pago = 'mixto' THEN mixto_tarjeta_centavos ELSE 0 END), 0) " +
            "FROM pedidos WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int calcTarjetaCentavos(long inicio, long fin);

    @Query("SELECT COALESCE(SUM(CASE " +
            "WHEN metodo_pago = 'qr' THEN total_centavos - descuento_centavos " +
            "WHEN metodo_pago = 'mixto' THEN mixto_qr_centavos ELSE 0 END), 0) " +
            "FROM pedidos WHERE estado = 'pagado' AND pagado_en BETWEEN :inicio AND :fin")
    int calcQrCentavos(long inicio, long fin);
}
