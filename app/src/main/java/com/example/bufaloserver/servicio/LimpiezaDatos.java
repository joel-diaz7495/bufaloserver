package com.example.bufaloserver.servicio;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.StatFs;
import android.util.Log;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.bufaloserver.db.AppDatabaseServidor;
import com.example.bufaloserver.db.dao.ResumenMensualDao;
import com.example.bufaloserver.db.entities.Auditoria;
import com.example.bufaloserver.db.entities.GastoGeneral;
import com.example.bufaloserver.db.entities.ResumenMensual;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ★ NUEVO — Limpieza de datos antiguos.
 *
 * El servidor conserva solo el mes actual + los (MESES_A_CONSERVAR - 1) meses anteriores
 * completos. Todo lo anterior se borra, pero ANTES se guarda una fila ResumenMensual por cada
 * mes con actividad, para seguir viendo los totales históricos en Reportes.
 *
 * QUÉ SE BORRA (solo lo anterior al corte):
 *   - pedidos CERRADOS (pagado / cancelado) → por CASCADE también sus líneas, salsas,
 *     modificadores y entregas. Los pendientes u otros estados NUNCA se borran.
 *   - gastos_diarios, movimientos_inventario, auditoria, tokens_emparejamiento.
 * QUÉ NO SE TOCA JAMÁS: gastos_generales (los gastos fijos recurrentes usan la fila del mes
 * más reciente como molde para los meses siguientes), productos, variantes, categorías,
 * usuarios ni dispositivos.
 *
 * SEGURIDAD:
 *   - Resúmenes + borrado + fila de auditoría van en UNA transacción: si algo falla, no se
 *     borra nada.
 *   - Un resumen ya guardado nunca se sobrescribe (el mes ya no tiene datos: recalcularlo
 *     daría ceros).
 *   - La PRIMERA limpieza nunca corre sola: solo la dispara un administrador a mano (borra de
 *     golpe todo lo anterior al corte). Después corre sola (ver ServidorService).
 *   - Nunca corren dos a la vez.
 *
 * Todos los métodos son SÍNCRONOS y tocan Room: llamarlos desde un hilo de fondo.
 */
public final class LimpiezaDatos {

    private static final String TAG = "LimpiezaDatos";

    /** Mes actual + los anteriores hasta completar este número (3 = mes actual + 2 anteriores). */
    public static final int MESES_A_CONSERVAR = 3;

    private static final String NOMBRE_BD = "bufaloserver.db";
    private static final String PREFS = "limpieza_datos";
    private static final String K_ULTIMA_MS = "ultima_ms";
    private static final String K_ULTIMA_DETALLE = "ultima_detalle";

    /**
     * La limpieza automática corre cuando el local está TRANQUILO: sin ningún pedido creado,
     * pagado o cancelado en los últimos 45 min. No depende de la hora porque el servidor suele
     * estar apagado de madrugada: normalmente corre sola apenas se enciende por la mañana,
     * antes de que empiece el servicio.
     */
    private static final long MIN_INACTIVIDAD_MS = 45L * 60 * 1000;
    /** Respaldo: si lleva más de ~35 días sin limpiar, corre aunque haya movimiento. */
    private static final long MAX_SIN_LIMPIAR_MS = 35L * 24 * 60 * 60 * 1000;

    private static final AtomicBoolean enCurso = new AtomicBoolean(false);

    private LimpiezaDatos() {}

    // ══════════════════════════════════════════════════════════════════════
    // Resultado de simular() / ejecutar()
    // ══════════════════════════════════════════════════════════════════════
    public static class Informe {
        public long corteMs;
        public int pedidos;
        public int itemsPedido;
        public int gastosDiarios;
        public int movimientosInventario;
        public int auditoria;
        public int tokens;
        /** Pedidos viejos que NO están cerrados (ej. pendientes olvidados): no se borran. */
        public int pedidosNoCerradosViejos;
        /** Meses {anio, mes} para los que se guarda (o se guardó) un resumen. */
        public List<int[]> mesesAResumir = new ArrayList<>();
        public long tamanoAntesBytes;
        public long tamanoDespuesBytes;
        public boolean ejecutada;
        public boolean vacuumOmitido;
        /** null = todo bien. */
        public String error;

        public int totalFilas() {
            return pedidos + gastosDiarios + movimientosInventario + auditoria + tokens;
        }

        public boolean hayAlgo() { return totalFilas() > 0; }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Corte y rangos
    // ══════════════════════════════════════════════════════════════════════

    /** Día 1 del mes (actual − (MESES_A_CONSERVAR − 1)) a las 00:00, hora del dispositivo. */
    public static long calcularCorte(long ahoraMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ahoraMs);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MONTH, -(MESES_A_CONSERVAR - 1));
        return c.getTimeInMillis();
    }

    /** Día 1 del mes siguiente a las 00:00: cuándo se mueve el corte (y se borra el mes más viejo). */
    public static long calcularProximaLimpieza(long ahoraMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ahoraMs);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MONTH, 1);
        return c.getTimeInMillis();
    }

    /** Mismo rango que ReportesActivity.rangoMes(): [día 1 00:00:00.000, último día 23:59:59.999]. */
    private static long[] rangoMes(int anio, int mes1a12) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(anio, mes1a12 - 1, 1, 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        long inicio = c.getTimeInMillis();
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 999);
        return new long[]{inicio, c.getTimeInMillis()};
    }

    private static int anioMes(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Resúmenes mensuales
    // ══════════════════════════════════════════════════════════════════════

    /** Totales de un mes con la MISMA regla que el PDF mensual de ReportesActivity. */
    static ResumenMensual calcularResumen(AppDatabaseServidor db, int anio, int mes) {
        long[] r = rangoMes(anio, mes);
        ResumenMensualDao e = db.resumenMensualDao();

        ResumenMensual x = new ResumenMensual();
        x.anio = anio;
        x.mes = mes;
        x.ventasCentavos       = e.calcVentasCentavos(r[0], r[1]);
        x.pedidosPagados       = e.calcPedidosPagados(r[0], r[1]);
        x.pedidosCancelados    = e.calcPedidosCancelados(r[0], r[1]);
        x.descuentosCentavos   = e.calcDescuentosCentavos(r[0], r[1]);
        x.efectivoCentavos     = e.calcEfectivoCentavos(r[0], r[1]);
        x.tarjetaCentavos      = e.calcTarjetaCentavos(r[0], r[1]);
        x.qrCentavos           = e.calcQrCentavos(r[0], r[1]);
        x.gastosDiariosCentavos = db.gastoDiarioDao().sumaMontoPorFecha(r[0], r[1]);

        // Gastos fijos: solo lo registrado en ESTE mes/año exacto. getVisiblesEnMes también
        // trae el "molde" recurrente de un mes anterior como referencia pendiente, y esa fila
        // NO cuenta como gasto de este mes (mismo criterio que ReportesActivity).
        int fijos = 0;
        for (GastoGeneral g : db.gastoGeneralDao().getVisiblesEnMesSync(anio, mes)) {
            if (g.anio == anio && g.mes == mes) fijos += g.montoCentavos;
        }
        x.gastosFijosCentavos = fijos;
        return x;
    }

    private static boolean tieneActividad(ResumenMensual r) {
        return r.pedidosPagados > 0 || r.pedidosCancelados > 0 || r.ventasCentavos > 0
                || r.gastosDiariosCentavos > 0 || r.gastosFijosCentavos > 0;
    }

    /** Meses anteriores al corte que tienen actividad y todavía no tienen resumen guardado. */
    private static List<int[]> mesesPorResumir(AppDatabaseServidor db, long corte) {
        List<int[]> out = new ArrayList<>();
        Long a = db.pedidoDao().getFechaCerradoMasAntiguoSync();
        Long b = db.gastoDiarioDao().getFechaMasAntiguaSync();
        Long min = (a == null) ? b : (b == null ? a : Long.valueOf(Math.min(a, b)));
        if (min == null || min >= corte) return out;

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(min);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        int guarda = 0; // tope de seguridad: 50 años
        while (c.getTimeInMillis() < corte && guarda++ < 600) {
            int anio = c.get(Calendar.YEAR);
            int mes = c.get(Calendar.MONTH) + 1;
            if (db.resumenMensualDao().getSync(anio, mes) == null
                    && tieneActividad(calcularResumen(db, anio, mes))) {
                out.add(new int[]{anio, mes});
            }
            c.add(Calendar.MONTH, 1);
        }
        return out;
    }

    /**
     * ¿Hay algo anterior al corte? Solo conteos (barato). simular() además calcula los resúmenes
     * de cada mes, que es más pesado: no conviene hacerlo en cada GET /limpieza/estado (lo pide
     * cada tablet al abrir Reportes) ni en cada chequeo horario.
     */
    private static boolean hayAlgoParaBorrar(AppDatabaseServidor db, long corte) {
        return db.pedidoDao().contarBorrablesAntesDe(corte) > 0
                || db.gastoDiarioDao().contarAntesDe(corte) > 0
                || db.movimientoInventarioDao().contarAntesDe(corte) > 0
                || db.auditoriaDao().contarAntesDe(corte) > 0
                || db.tokenEmparejamientoDao().contarAntesDe(corte) > 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Simular (no borra nada)
    // ══════════════════════════════════════════════════════════════════════
    public static Informe simular(Context ctx) {
        Context app = ctx.getApplicationContext();
        AppDatabaseServidor db = AppDatabaseServidor.getInstance(app);
        Informe inf = new Informe();
        inf.corteMs = calcularCorte(System.currentTimeMillis());
        inf.pedidos               = db.pedidoDao().contarBorrablesAntesDe(inf.corteMs);
        inf.itemsPedido           = db.pedidoDao().contarItemsBorrablesAntesDe(inf.corteMs);
        inf.pedidosNoCerradosViejos = db.pedidoDao().contarNoCerradosAntesDe(inf.corteMs);
        inf.gastosDiarios         = db.gastoDiarioDao().contarAntesDe(inf.corteMs);
        inf.movimientosInventario = db.movimientoInventarioDao().contarAntesDe(inf.corteMs);
        inf.auditoria             = db.auditoriaDao().contarAntesDe(inf.corteMs);
        inf.tokens                = db.tokenEmparejamientoDao().contarAntesDe(inf.corteMs);
        inf.mesesAResumir         = mesesPorResumir(db, inf.corteMs);
        inf.tamanoAntesBytes      = tamanoBase(app);
        inf.tamanoDespuesBytes    = inf.tamanoAntesBytes;
        return inf;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Ejecutar
    // ══════════════════════════════════════════════════════════════════════
    public static Informe ejecutar(Context ctx, long usuarioId, String nombreUsuario, String rol) {
        Informe inf = new Informe();
        if (!enCurso.compareAndSet(false, true)) {
            inf.error = "Ya hay una limpieza en curso";
            return inf;
        }
        try {
            final Context app = ctx.getApplicationContext();
            final AppDatabaseServidor db = AppDatabaseServidor.getInstance(app);
            final long ahora = System.currentTimeMillis();
            final long corte = calcularCorte(ahora);
            inf.corteMs = corte;
            inf.tamanoAntesBytes = tamanoBase(app);

            db.runInTransaction(() -> {
                // 1) Resúmenes PRIMERO: si algo falla de acá en adelante, todo se deshace.
                for (int[] ym : mesesPorResumir(db, corte)) {
                    db.resumenMensualDao().insertarSiNoExiste(calcularResumen(db, ym[0], ym[1]));
                    if (db.resumenMensualDao().getSync(ym[0], ym[1]) == null) {
                        throw new IllegalStateException("No se pudo guardar el resumen de "
                                + ym[0] + "-" + ym[1] + "; no se borra nada");
                    }
                    inf.mesesAResumir.add(ym);
                }

                // 2) Conteos previos (las líneas se borran por CASCADE, hay que contarlas antes).
                inf.itemsPedido = db.pedidoDao().contarItemsBorrablesAntesDe(corte);
                inf.pedidosNoCerradosViejos = db.pedidoDao().contarNoCerradosAntesDe(corte);

                // 3) Borrado. gastos_generales NO se toca.
                inf.pedidos               = db.pedidoDao().borrarBorrablesAntesDe(corte);
                inf.gastosDiarios         = db.gastoDiarioDao().borrarAntesDe(corte);
                inf.movimientosInventario = db.movimientoInventarioDao().borrarAntesDe(corte);
                inf.auditoria             = db.auditoriaDao().borrarAntesDe(corte);
                inf.tokens                = db.tokenEmparejamientoDao().borrarAntesDe(corte);

                // 4) Auditoría de la propia limpieza (se inserta DESPUÉS de borrar la vieja).
                if (inf.hayAlgo() || !inf.mesesAResumir.isEmpty()) {
                    Auditoria a = new Auditoria();
                    a.usuarioId = usuarioId;
                    a.nombreUsuario = nombreUsuario;
                    a.rol = rol;
                    a.accion = Auditoria.ACCION_LIMPIEZA_DATOS;
                    a.detalle = detalleLegible(inf);
                    a.entidadTipo = "limpieza";
                    a.entidadId = 0;
                    a.creadoEn = ahora;
                    db.auditoriaDao().insert(a);
                }
            });

            // Marca que ya hubo una limpieza (habilita la automática a partir de ahora).
            prefs(app).edit()
                    .putLong(K_ULTIMA_MS, ahora)
                    .putString(K_ULTIMA_DETALLE, inf.hayAlgo() ? detalleLegible(inf)
                            : "Sin datos antiguos para borrar")
                    .apply();

            // 5) VACUUM FUERA de la transacción: sin esto el archivo no se achica.
            if (inf.hayAlgo()) vacuum(app, db, inf);

            inf.tamanoDespuesBytes = tamanoBase(app);
            inf.ejecutada = true;
            Log.i(TAG, "Limpieza OK: " + detalleLegible(inf));
        } catch (Exception e) {
            Log.e(TAG, "La limpieza falló — la transacción se deshizo, no se borró nada", e);
            inf.error = "La limpieza falló y no se borró nada: " + e.getMessage();
            inf.ejecutada = false;
        } finally {
            enCurso.set(false);
        }
        return inf;
    }

    private static void vacuum(Context app, AppDatabaseServidor db, Informe inf) {
        try {
            File f = app.getDatabasePath(NOMBRE_BD);
            long libre = new StatFs(f.getParentFile().getAbsolutePath()).getAvailableBytes();
            // VACUUM reescribe la base: necesita hasta ~2x su tamaño libre.
            if (libre < inf.tamanoAntesBytes * 2 + 16L * 1024 * 1024) {
                inf.vacuumOmitido = true;
                Log.w(TAG, "VACUUM omitido: poco espacio libre (" + libre + " bytes)");
                return;
            }
            SupportSQLiteDatabase sdb = db.getOpenHelper().getWritableDatabase();
            sdb.execSQL("VACUUM");
            try (Cursor c = sdb.query("PRAGMA wal_checkpoint(TRUNCATE)")) {
                c.moveToFirst();
            } catch (Exception ignorado) {
                // El VACUUM ya se hizo; el checkpoint solo acelera que el archivo -wal se vacíe.
            }
        } catch (Exception e) {
            inf.vacuumOmitido = true;
            Log.w(TAG, "VACUUM falló (los datos ya se borraron bien; el archivo se achicará luego)", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Estado / automática
    // ══════════════════════════════════════════════════════════════════════

    public static boolean primeraLimpiezaPendiente(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (prefs(app).getLong(K_ULTIMA_MS, 0) > 0) return false;
        return AppDatabaseServidor.getInstance(app).resumenMensualDao().contarSync() == 0;
    }

    /**
     * Llamado cada hora por ServidorService (y 5 min después de encender el servidor).
     * Solo corre si: ya hubo una limpieza manual previa, hay algo para borrar, y el local
     * está tranquilo (sin pedidos en los últimos 45 min) o lleva > 35 días sin limpiarse.
     */
    public static void ejecutarAutomaticaSiCorresponde(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            if (primeraLimpiezaPendiente(app)) return;
            if (!hayAlgoParaBorrar(AppDatabaseServidor.getInstance(app), calcularCorte(System.currentTimeMillis()))) return;

            long ahora = System.currentTimeMillis();
            Long ultimaActividad = AppDatabaseServidor.getInstance(app).pedidoDao().getUltimaActividadSync();
            boolean tranquilo = ultimaActividad == null || ultimaActividad <= 0
                    || (ahora - ultimaActividad) >= MIN_INACTIVIDAD_MS;
            long ultima = prefs(app).getLong(K_ULTIMA_MS, 0);
            boolean atrasada = ultima > 0 && (ahora - ultima) > MAX_SIN_LIMPIAR_MS;
            if (!tranquilo && !atrasada) return;

            Informe inf = ejecutar(app, 0, "Sistema", "sistema");
            if (inf.error != null) Log.w(TAG, "Limpieza automática: " + inf.error);
        } catch (Throwable e) {
            // Throwable a propósito: si esto lanza algo sin atrapar, el ScheduledExecutor cancela
            // TODAS las ejecuciones futuras y la limpieza automática dejaría de correr en silencio.
            Log.e(TAG, "Limpieza automática falló", e);
        }
    }

    public static JSONObject estadoComoJson(Context ctx) throws JSONException {
        Context app = ctx.getApplicationContext();
        AppDatabaseServidor db = AppDatabaseServidor.getInstance(app);
        long ahora = System.currentTimeMillis();
        long corte = calcularCorte(ahora);
        int corteYM = anioMes(corte);
        boolean primera = primeraLimpiezaPendiente(app);

        JSONObject o = new JSONObject();
        o.put("mesesConservados", MESES_A_CONSERVAR);
        o.put("corteMs", corte);
        o.put("corteAnio", corteYM / 100);
        o.put("corteMes", corteYM % 100); // mes más antiguo que se conserva
        o.put("proximaLimpiezaMs", calcularProximaLimpieza(ahora));
        o.put("primeraLimpiezaPendiente", primera);
        o.put("ultimaLimpiezaMs", prefs(app).getLong(K_ULTIMA_MS, 0));
        o.put("ultimaLimpiezaDetalle", prefs(app).getString(K_ULTIMA_DETALLE, ""));
        o.put("tamanoBaseBytes", tamanoBase(app));
        o.put("resumenesGuardados", db.resumenMensualDao().contarSync());
        o.put("hayAlgoParaLimpiar", hayAlgoParaBorrar(db, corte));
        o.put("enCurso", enCurso.get());

        // Hasta qué mes se puede retroceder en Reportes. Antes de la primera limpieza no hay
        // límite (todavía existen todos los datos); después, hasta el resumen más antiguo.
        if (!primera) {
            Integer resumenYM = db.resumenMensualDao().getMesMasAntiguoSync();
            int limite = (resumenYM != null) ? Math.min(resumenYM, corteYM) : corteYM;
            o.put("limiteAnio", limite / 100);
            o.put("limiteMes", limite % 100);
        }
        return o;
    }

    public static JSONObject informeComoJson(Informe inf) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ejecutada", inf.ejecutada);
        o.put("corteMs", inf.corteMs);
        o.put("pedidos", inf.pedidos);
        o.put("itemsPedido", inf.itemsPedido);
        o.put("gastosDiarios", inf.gastosDiarios);
        o.put("movimientosInventario", inf.movimientosInventario);
        o.put("auditoria", inf.auditoria);
        o.put("tokens", inf.tokens);
        o.put("pedidosNoCerradosViejos", inf.pedidosNoCerradosViejos);
        o.put("tamanoAntesBytes", inf.tamanoAntesBytes);
        o.put("tamanoDespuesBytes", inf.tamanoDespuesBytes);
        o.put("vacuumOmitido", inf.vacuumOmitido);
        if (inf.error != null) o.put("error", inf.error);
        org.json.JSONArray meses = new org.json.JSONArray();
        for (int[] ym : inf.mesesAResumir) meses.put(String.format(Locale.US, "%04d-%02d", ym[0], ym[1]));
        o.put("mesesAResumir", meses);
        return o;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Utilidades
    // ══════════════════════════════════════════════════════════════════════

    /** Texto para mostrar al admin (diálogo de simulación) y para la auditoría. */
    public static String detalleLegible(Informe inf) {
        String corte = new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(new Date(inf.corteMs));
        StringBuilder sb = new StringBuilder();
        sb.append("Datos anteriores al ").append(corte).append(": ")
          .append(inf.pedidos).append(" pedidos (").append(inf.itemsPedido).append(" líneas), ")
          .append(inf.gastosDiarios).append(" gastos diarios, ")
          .append(inf.movimientosInventario).append(" movimientos de inventario, ")
          .append(inf.auditoria).append(" registros de auditoría");
        if (!inf.mesesAResumir.isEmpty()) {
            sb.append(". Resúmenes guardados: ");
            for (int i = 0; i < inf.mesesAResumir.size(); i++) {
                int[] ym = inf.mesesAResumir.get(i);
                if (i > 0) sb.append(", ");
                sb.append(String.format(Locale.US, "%04d-%02d", ym[0], ym[1]));
            }
        }
        return sb.toString();
    }

    /** Tamaño real de la base en disco (archivo principal + WAL). */
    public static long tamanoBase(Context ctx) {
        File f = ctx.getApplicationContext().getDatabasePath(NOMBRE_BD);
        long total = f.length();
        File wal = new File(f.getPath() + "-wal");
        if (wal.exists()) total += wal.length();
        return total;
    }

    public static String formatearBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
