package com.example.bufaloserver;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.bufaloserver.servicio.ServidorService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * Única pantalla del servidor: iniciar/detener el Foreground Service, y
 * mostrar todo lo que hace falta para configurar un cliente nuevo — IP:puerto
 * en texto, el mismo dato como QR, y el nombre de la red Wi-Fi actual (para
 * confirmar a simple vista "estoy en la red correcta" antes de generar el QR,
 * porque si la tablet está en la red equivocada, el QR va a tener una IP que
 * ningún cliente en la red del local va a poder alcanzar).
 *
 * NOTA: el botón de "Gasto rápido sin conexión" que se agregó acá en una
 * versión anterior se sacó — esa función ya existe del lado cliente
 * (RegistroGastoRapidoActivity + GastoPendienteCache + SincronizadorPendientes)
 * y se sincroniza contra POST /gastos/pendientes, implementado ahora en
 * ServidorHttp. Esta pantalla no necesita duplicarla.
 */
public class EstadoServidorActivity extends AppCompatActivity {

    private TextView tvEstado;
    private TextView tvRed;
    private TextView tvIpPuerto;
    private TextView tvClientesConectados;
    private ImageView imgQr;
    /** ★ NUEVO — ver verificarPrimerAdmin(). Solo visible mientras no exista
     *  ningún usuario todavía; desaparece para siempre apenas se crea el
     *  primero. */
    private Button btnPrimerAdmin;
    private Button btnToggle;
    private Button btnBateria;
    /** ★ NUEVO — resumen de una línea del estado de la limpieza de datos. */
    private TextView tvLimpieza;
    private boolean servidorEncendido = false;
    /** 0=apagado, 1=encendido, 2=iniciando. Evita reescribir textos y crear
     *  un drawable nuevo cada 2 s cuando el estado no cambió. */
    private int estadoUiActual = -1;

    /** Último "ip:puerto" mostrado, para no regenerar el QR en cada refresco. */
    private String ultimaIpPuerto = null;
    /** Contador para refrescar la info de red (IP/SSID) cada ~10 s. */
    private int ticksRed = 0;

    /** Refresca "N tablets conectadas" cada 2s mientras la pantalla está visible. */
    private final android.os.Handler handlerClientes = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refrescarClientesRunnable = new Runnable() {
        @Override
        public void run() {
            // ★ FIX — el estado del servidor y la IP también se refrescan
            // solos: antes el QR quedaba congelado con la IP del arranque y,
            // si DHCP la cambiaba (o el WiFi se reconectaba), los celulares
            // escaneaban una dirección que ya no existía.
            sincronizarUiConServicioReal();
            actualizarClientesConectados();
            if (++ticksRed >= 5) {
                ticksRed = 0;
                actualizarInfoRed();
            }
            handlerClientes.postDelayed(this, 2000);
        }
    };

    private final ActivityResultLauncher<String[]> pedirPermisoUbicacion =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
                    resultado -> actualizarInfoRed()); // con o sin permiso, refresca lo que pueda mostrar

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFF5F5F5);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        TextView tvTitulo = new TextView(this);
        tvTitulo.setText("Servidor del local");
        tvTitulo.setTextSize(22f);
        tvTitulo.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitulo);

        tvEstado = new TextView(this);
        tvEstado.setTextSize(15f);
        tvEstado.setTextColor(0xFF888888);
        LinearLayout.LayoutParams estadoP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        estadoP.topMargin = dp(8);
        tvEstado.setLayoutParams(estadoP);
        root.addView(tvEstado);

        tvRed = new TextView(this);
        tvRed.setTextSize(13f);
        tvRed.setTextColor(0xFF888888);
        LinearLayout.LayoutParams redP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        redP.topMargin = dp(4);
        tvRed.setLayoutParams(redP);
        root.addView(tvRed);

        tvIpPuerto = new TextView(this);
        tvIpPuerto.setTextSize(20f);
        tvIpPuerto.setTypeface(null, Typeface.BOLD);
        tvIpPuerto.setTextColor(0xFF2E7D32);
        LinearLayout.LayoutParams ipP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ipP.topMargin = dp(16);
        tvIpPuerto.setLayoutParams(ipP);
        root.addView(tvIpPuerto);

        tvClientesConectados = new TextView(this);
        tvClientesConectados.setTextSize(13f);
        tvClientesConectados.setTextColor(0xFF555555);
        LinearLayout.LayoutParams clientesP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clientesP.topMargin = dp(6);
        tvClientesConectados.setLayoutParams(clientesP);
        root.addView(tvClientesConectados);

        // ── QR ───────────────────────────────────────────────────────────────
        // Contenido exacto: el mismo "ip:puerto" que ya valida
        // ConfiguracionServidorActivity.PATRON_IP_PUERTO del lado cliente —
        // texto plano, nada de JSON (contrato acordado con el cliente).
        imgQr = new ImageView(this);
        LinearLayout.LayoutParams qrP = new LinearLayout.LayoutParams(dp(220), dp(220));
        qrP.topMargin = dp(20);
        imgQr.setLayoutParams(qrP);
        GradientDrawable qrBg = new GradientDrawable();
        qrBg.setColor(Color.WHITE);
        qrBg.setStroke(dp(1), 0xFFDDDDDD);
        imgQr.setBackground(qrBg);
        imgQr.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(imgQr);

        TextView tvQrLabel = new TextView(this);
        tvQrLabel.setText("Escaneá esto desde la app del celular al conectar");
        tvQrLabel.setTextSize(11f);
        tvQrLabel.setTextColor(0xFFAAAAAA);
        tvQrLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams qrLabelP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qrLabelP.topMargin = dp(6);
        tvQrLabel.setLayoutParams(qrLabelP);
        root.addView(tvQrLabel);

        btnToggle = new Button(this);
        btnToggle.setAllCaps(false);
        btnToggle.setTextColor(0xFFFFFFFF);
        btnToggle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams btnP = new LinearLayout.LayoutParams(dp(240), dp(56));
        btnP.topMargin = dp(28);
        btnToggle.setLayoutParams(btnP);
        btnToggle.setOnClickListener(v -> toggleServidor());
        root.addView(btnToggle);

        // ── Botón: configurar la impresora del servidor ─────────────────────
        Button btnImpresora = new Button(this);
        btnImpresora.setAllCaps(false);
        btnImpresora.setText("🖨️ Configurar impresora");
        btnImpresora.setTextColor(0xFF1C1C1E);
        LinearLayout.LayoutParams btnImpP = new LinearLayout.LayoutParams(dp(240), dp(48));
        btnImpP.topMargin = dp(14);
        btnImpresora.setLayoutParams(btnImpP);
        GradientDrawable bgImp = new GradientDrawable();
        bgImp.setColor(0xFFEFEFEF);
        bgImp.setCornerRadius(dp(12));
        btnImpresora.setBackground(bgImp);
        btnImpresora.setOnClickListener(v -> startActivity(new Intent(this,
                com.example.bufaloserver.print.PrinterConfigActivityServidor.class)));
        root.addView(btnImpresora);

        // ── Botón: exceptuar de optimización de batería ─────────────────────
        // Solo se muestra si hace falta (si ya está exceptuada, no molesta con
        // esto). Sin esto, en Android 8+ (sobre todo con los "administradores
        // de batería" propios de Xiaomi/Samsung/Huawei) el sistema puede
        // limitar la CPU y la red de este proceso con la pantalla apagada,
        // aunque sea un Foreground Service — el Foreground Service evita que
        // lo MATEN, pero no evita que lo LIMITEN. Esta es la pieza que falta
        // para eso.
        btnBateria = new Button(this);
        btnBateria.setAllCaps(false);
        btnBateria.setText("🔋 Permitir uso en segundo plano");
        btnBateria.setTextColor(0xFF1C1C1E);
        LinearLayout.LayoutParams btnBatP = new LinearLayout.LayoutParams(dp(240), dp(48));
        btnBatP.topMargin = dp(10);
        btnBateria.setLayoutParams(btnBatP);
        GradientDrawable bgBat = new GradientDrawable();
        bgBat.setColor(0xFFFFF3E0);
        bgBat.setCornerRadius(dp(12));
        btnBateria.setBackground(bgBat);
        btnBateria.setOnClickListener(v -> pedirExcepcionBateria());
        root.addView(btnBateria);

        // ── ★ NUEVO — Limpieza de datos antiguos ────────────────────────────
        // Se conservan solo el mes actual + 2 anteriores; lo anterior se borra (después de
        // guardar un resumen por mes). Ver servicio/LimpiezaDatos. Primero se SIMULA (muestra
        // qué se borraría) y recién después se puede ejecutar, escribiendo BORRAR.
        Button btnLimpieza = new Button(this);
        btnLimpieza.setAllCaps(false);
        btnLimpieza.setText("🧹 Limpieza de datos");
        btnLimpieza.setTextColor(0xFF1C1C1E);
        LinearLayout.LayoutParams btnLimP = new LinearLayout.LayoutParams(dp(240), dp(48));
        btnLimP.topMargin = dp(10);
        btnLimpieza.setLayoutParams(btnLimP);
        GradientDrawable bgLim = new GradientDrawable();
        bgLim.setColor(0xFFEFEFEF);
        bgLim.setCornerRadius(dp(12));
        btnLimpieza.setBackground(bgLim);
        btnLimpieza.setOnClickListener(v -> abrirLimpiezaDatos());
        root.addView(btnLimpieza);

        tvLimpieza = new TextView(this);
        tvLimpieza.setTextSize(11.5f);
        tvLimpieza.setTextColor(0xFF888888);
        tvLimpieza.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tvLimP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLimP.topMargin = dp(4);
        tvLimpieza.setLayoutParams(tvLimP);
        root.addView(tvLimpieza);

        // ★ NUEVO — reemplaza al diálogo automático que aparecía apenas
        // arrancaba la pantalla (ver verificarPrimerAdmin() más abajo): ese
        // diálogo tapaba TODA la pantalla, incluido el QR de arriba — así
        // que si alguien necesitaba escanear el QR para configurar OTRO
        // celular mientras recién se instalaba el servidor, no podía, hasta
        // no terminar de crear el administrador. Ahora es un botón que solo
        // aparece si todavía no hay ningún usuario creado (verificarPrimerAdmin
        // lo esconde permanentemente apenas se crea el primero) — el QR de
        // arriba queda siempre visible y escaneable desde el primer segundo.
        btnPrimerAdmin = new Button(this);
        btnPrimerAdmin.setAllCaps(false);
        btnPrimerAdmin.setText("👤 Conectar primer administrador");
        btnPrimerAdmin.setTextColor(0xFFFFFFFF);
        btnPrimerAdmin.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams btnPrimerAdminP = new LinearLayout.LayoutParams(dp(240), dp(48));
        btnPrimerAdminP.topMargin = dp(10);
        btnPrimerAdmin.setLayoutParams(btnPrimerAdminP);
        GradientDrawable bgPrimerAdmin = new GradientDrawable();
        bgPrimerAdmin.setColor(0xFFC8102E);
        bgPrimerAdmin.setCornerRadius(dp(12));
        btnPrimerAdmin.setBackground(bgPrimerAdmin);
        btnPrimerAdmin.setVisibility(View.GONE); // verificarPrimerAdmin() decide si se muestra
        btnPrimerAdmin.setOnClickListener(v -> mostrarDialogoPrimerAdmin());
        root.addView(btnPrimerAdmin);

        // ★ FIX — sin ScrollView, en horizontal (alto de pantalla reducido)
        // el LinearLayout se recorta contra el borde y los botones de abajo
        // (impresora, batería, e incluso "Iniciar servidor") quedan fuera
        // del área visible/tocable. Mismo patrón que ya usa
        // PrinterConfigActivityServidor (setContentView(scroll)).
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        actualizarInfoRed();
        // ★ FIX: antes acá se llamaba directo a actualizarUiApagado(), sin
        // preguntar nada. Como Android destruye y recrea esta Activity cada
        // vez que rota la tablet (no hay android:configChanges declarado
        // para esta pantalla), eso hacía que la UI mostrara "🔴 Servidor
        // detenido" apenas se giraba la pantalla — aunque el
        // ServidorService (Foreground Service) siguiera corriendo
        // normalmente de fondo, sin haberse tocado. No era un apagado real,
        // era la UI mintiendo sobre el estado.
        //
        // Ahora se pregunta el estado real a través de la instancia estática
        // que ya expone ServidorService (la misma que usa
        // actualizarClientesConectados()), y se refleja tal cual está.
        sincronizarUiConServicioReal();
        actualizarVisibilidadBotonBateria();

        // ★ FIX ANDROID 13+ — en API 33+ el SSID se lee con
        // NEARBY_WIFI_DEVICES (ya no hace falta ubicación) y las
        // notificaciones necesitan permiso explícito. Se piden las dos en
        // UNA sola solicitud: si se lanzaran por separado junto con la de
        // ubicación, Android puede descartar los diálogos superpuestos y
        // quedar sin pedir la notificación. En API < 33 se pide ubicación.
        if (Build.VERSION.SDK_INT >= 33) {
            java.util.ArrayList<String> faltantes = new java.util.ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, "android.permission.NEARBY_WIFI_DEVICES")
                    != PackageManager.PERMISSION_GRANTED) {
                faltantes.add("android.permission.NEARBY_WIFI_DEVICES");
            }
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                faltantes.add("android.permission.POST_NOTIFICATIONS");
            }
            if (!faltantes.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        faltantes.toArray(new String[0]), 201);
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pedirPermisoUbicacion.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }

        verificarPrimerAdmin();
    }

    /**
     * ★ NUEVO — reemplaza al admin sembrado con PIN fijo 1234 que se creaba
     * antes en cada instalación (ver AppDatabaseServidor, SEED_CALLBACK: esa
     * era una credencial por defecto conocida, un hueco de seguridad en sí
     * mismo). Ahora, si la tabla de usuarios está vacía, se le pide a quien
     * está instalando el servidor que elija su propio nombre y PIN.
     *
     * ★ FIX — antes esto disparaba el diálogo automáticamente (
     * runOnUiThread(this::mostrarDialogoPrimerAdmin)) apenas se detectaba
     * que no había usuarios, tapando toda la pantalla (incluido el QR de
     * arriba) desde el primer segundo. Ahora solo decide si mostrar u
     * ocultar btnPrimerAdmin — el diálogo se abre recién si alguien lo toca.
     * Se sigue llamando cada vez que la Activity se recrea (rotación,
     * volver de otra pantalla) para que el botón desaparezca solo, para
     * siempre, apenas se termina de crear el primer usuario.
     *
     * Corre en dbExecutor porque getTodosSync() toca la base de datos —
     * nunca en el hilo principal.
     */
    private void verificarPrimerAdmin() {
        com.example.bufaloserver.db.AppDatabaseServidor.dbExecutor.execute(() -> {
            com.example.bufaloserver.db.AppDatabaseServidor db =
                    com.example.bufaloserver.db.AppDatabaseServidor.getInstance(getApplicationContext());
            boolean hayUsuarios = !db.usuarioDao().getTodosSync().isEmpty();
            runOnUiThread(() -> btnPrimerAdmin.setVisibility(hayUsuarios ? View.GONE : View.VISIBLE));
        });
    }

    private void mostrarDialogoPrimerAdmin() {
        if (isFinishing()) return;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(8));

        TextView tvAviso = new TextView(this);
        tvAviso.setText("Todavía no hay ningún administrador configurado. Elegí un nombre y un PIN de 4 dígitos para el primer administrador — con este PIN vas a poder entrar desde la app cliente después de emparejar tu celular.");
        tvAviso.setTextSize(13f);
        tvAviso.setTextColor(0xFF666666);
        tvAviso.setPadding(0, 0, 0, dp(16));
        root.addView(tvAviso);

        android.widget.EditText etNombre = new android.widget.EditText(this);
        etNombre.setHint("Nombre (ej. Juan)");
        root.addView(etNombre);

        android.widget.EditText etPin = new android.widget.EditText(this);
        etPin.setHint("PIN de 4 dígitos");
        etPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPin.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(4) });
        LinearLayout.LayoutParams pinP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinP.topMargin = dp(8);
        etPin.setLayoutParams(pinP);
        root.addView(etPin);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Crear primer administrador")
                .setView(root)
                // ★ FIX — antes era false ("sin admin, el servidor no sirve de
                // nada, no dejar cancelar en el aire"), porque este diálogo ERA
                // el único camino posible al arrancar. Ahora es opt-in (se abre
                // solo si se toca btnPrimerAdmin) y el resto de la pantalla
                // (QR incluido) sigue disponible de fondo, así que cancelar ya
                // no deja a nadie sin salida — el botón sigue ahí para volver
                // a intentarlo cuando quieran.
                .setCancelable(true)
                .setPositiveButton("Crear", null) // se pisa abajo para poder validar antes de cerrar
                .create();

        dialog.setOnShowListener(d -> {
            Button btnCrear = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btnCrear.setOnClickListener(v -> {
                String nombre = etNombre.getText().toString().trim();
                String pin = etPin.getText().toString().trim();
                if (nombre.isEmpty()) {
                    etNombre.setError("Obligatorio");
                    return;
                }
                if (!pin.matches("\\d{4}")) {
                    etPin.setError("4 dígitos");
                    return;
                }
                com.example.bufaloserver.db.AppDatabaseServidor.dbExecutor.execute(() -> {
                    com.example.bufaloserver.db.AppDatabaseServidor db =
                            com.example.bufaloserver.db.AppDatabaseServidor.getInstance(getApplicationContext());
                    com.example.bufaloserver.db.entities.Usuario admin = new com.example.bufaloserver.db.entities.Usuario(
                            nombre, pin, com.example.bufaloserver.db.entities.RolUsuario.ADMINISTRADOR);
                    long adminId = db.usuarioDao().insert(admin);

                    // ★ Genera de una el código de emparejamiento para este mismo
                    // admin — NO se lo puede mandar a "Gestión de usuarios" a
                    // buscarlo, porque esa pantalla todavía le exige estar
                    // logueado, y para loguearse primero necesita emparejar su
                    // celular. Este es el único momento en que el propio
                    // servidor genera el QR sin pasar por una sesión.
                    com.example.bufaloserver.db.entities.TokenEmparejamiento t =
                            new com.example.bufaloserver.db.entities.TokenEmparejamiento();
                    t.token = generarTokenSeguroLocal();
                    t.usuarioId = adminId;
                    t.creadoEn = System.currentTimeMillis();
                    t.expiraEn = t.creadoEn + com.example.bufaloserver.db.entities.TokenEmparejamiento.EXPIRA_MS;
                    db.tokenEmparejamientoDao().insert(t);

                    runOnUiThread(() -> {
                        dialog.dismiss();
                        btnPrimerAdmin.setVisibility(View.GONE); // ★ NUEVO — desaparece para siempre, sin esperar a recrear la Activity
                        mostrarDialogoQrPrimerAdmin(nombre, t.token);
                    });
                });
            });
        });

        dialog.show();
    }

    /** Muestra el QR de emparejamiento del primer admin recién creado. Mismo
     *  formato de contenido que espera EmparejarDispositivoActivity del lado
     *  cliente ("BUFALO-EMPAREJAR:" + token) y la misma técnica de dibujo
     *  (QRCodeWriter) que ya usa esta pantalla para el QR de ip:puerto. */
    private void mostrarDialogoQrPrimerAdmin(String nombreAdmin, String token) {
        if (isFinishing()) return;

        String contenido = "BUFALO-EMPAREJAR:" + token;
        Bitmap bmp = null;
        try {
            int tam = dp(220);
            BitMatrix matrix = new QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, tam, tam);
            bmp = Bitmap.createBitmap(tam, tam, Bitmap.Config.RGB_565);
            for (int x = 0; x < tam; x++) {
                for (int y = 0; y < tam; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
        } catch (WriterException ignored) { /* bmp queda null, se avisa abajo */ }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(16), dp(24), dp(8));

        TextView tvTexto = new TextView(this);
        tvTexto.setText("Administrador \"" + nombreAdmin + "\" creado ✅\n\nEscaneá este código desde tu celular, en la app cliente (pantalla \"Dispositivo no autorizado\"). Vale por 5 minutos y una sola vez.");
        tvTexto.setTextSize(13f);
        tvTexto.setGravity(Gravity.CENTER);
        tvTexto.setPadding(0, 0, 0, dp(16));
        root.addView(tvTexto);

        if (bmp != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(220), dp(220)));
            root.addView(iv);
        }

        new AlertDialog.Builder(this)
                .setView(root)
                .setPositiveButton("Listo", null)
                .setNegativeButton("Generar otro código", (d2, w) -> verificarPrimerAdminForzarNuevoToken(nombreAdmin))
                .show();
    }

    /** El código anterior venció antes de escanearlo — se pide uno nuevo para
     *  el mismo admin recién creado (ya no hace falta re-tipear nombre/pin). */
    private void verificarPrimerAdminForzarNuevoToken(String nombreAdmin) {
        com.example.bufaloserver.db.AppDatabaseServidor.dbExecutor.execute(() -> {
            com.example.bufaloserver.db.AppDatabaseServidor db =
                    com.example.bufaloserver.db.AppDatabaseServidor.getInstance(getApplicationContext());
            com.example.bufaloserver.db.entities.Usuario admin = null;
            for (com.example.bufaloserver.db.entities.Usuario u : db.usuarioDao().getTodosSync()) {
                if (u.nombre.equals(nombreAdmin)) { admin = u; break; }
            }
            if (admin == null) return;
            com.example.bufaloserver.db.entities.TokenEmparejamiento t =
                    new com.example.bufaloserver.db.entities.TokenEmparejamiento();
            t.token = generarTokenSeguroLocal();
            t.usuarioId = admin.id;
            t.creadoEn = System.currentTimeMillis();
            t.expiraEn = t.creadoEn + com.example.bufaloserver.db.entities.TokenEmparejamiento.EXPIRA_MS;
            db.tokenEmparejamientoDao().insert(t);
            String nombreFinal = admin.nombre;
            String tokenFinal = t.token;
            runOnUiThread(() -> mostrarDialogoQrPrimerAdmin(nombreFinal, tokenFinal));
        });
    }

    /** Mismo criterio de generación que ServidorHttp.generarTokenSeguro() —
     *  32 bytes random en base64 url-safe. Duplicado acá a propósito: esta
     *  Activity no depende de que ServidorHttp/ServidorService estén
     *  corriendo todavía para poder generar el primer código. */
    private String generarTokenSeguroLocal() {
        byte[] datos = new byte[32];
        new java.security.SecureRandom().nextBytes(datos);
        return android.util.Base64.encodeToString(datos,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    /**
     * true si Android ya NO le va a limitar la CPU/red a esta app en Doze.
     * En API < 23 no existe el concepto, así que se considera "sin problema".
     */
    private boolean estaExceptuadaDeBateria() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void actualizarVisibilidadBotonBateria() {
        btnBateria.setVisibility(estaExceptuadaDeBateria() ? View.GONE : View.VISIBLE);
    }

    /**
     * Lanza el diálogo del sistema para pedir la excepción. Es un diálogo
     * nativo de Android (no un permiso en runtime normal) — el usuario puede
     * aceptar o rechazar libremente. No hace falta pedirlo de nuevo si ya
     * fue concedido; por eso el botón se oculta apenas se detecta que ya
     * está exceptuada (ver onResume).
     */
    @android.annotation.SuppressLint("BatteryLife")
    private void pedirExcepcionBateria() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            // Algunos fabricantes (Xiaomi/Huawei) no respetan este intent
            // estándar — en ese caso, mandar a la pantalla general de la app
            // para que el usuario busque "Batería" a mano.
            Intent detalle = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            detalle.setData(Uri.parse("package:" + getPackageName()));
            startActivity(detalle);
        }
    }

    /**
     * Refleja en la UI el estado REAL del servicio (en vez de asumir uno).
     * Se usa en onCreate para no mentir después de que la Activity se
     * recrea por rotación (ver comentario en onCreate) y también sirve como
     * red de seguridad en onStart, por si el servicio se cayó o arrancó por
     * fuera de esta pantalla (ej. START_STICKY reiniciándolo solo).
     */
    private void sincronizarUiConServicioReal() {
        ServidorService servicio = ServidorService.getInstancia();
        if (servicio == null) {
            actualizarUiApagado();
        } else if (servicio.estaSaludable()) {
            actualizarUiEncendido();
        } else {
            // ★ FIX — servicio vivo pero sin puertos: antes se mostraba
            // "🟢 activo" igual, y el usuario no tenía forma de saber que en
            // realidad nadie podía conectarse. Ahora se ve el estado real.
            actualizarUiIniciando();
        }
    }

    /**
     * Antes esto encendía/apagaba directo con un solo toque. Ahora pide
     * confirmación en los dos sentidos, para evitar que un toque accidental
     * (por ejemplo al girar la tablet y que el layout se reacomode justo
     * bajo el dedo) apague el servidor en medio de un turno, o lo prenda
     * sin querer.
     */
    private void toggleServidor() {
        if (servidorEncendido) {
            confirmarApagarServidor();
        } else {
            confirmarActivarServidor();
        }
    }

    private void confirmarActivarServidor() {
        new AlertDialog.Builder(this)
                .setTitle("Activar servidor")
                .setMessage("¿Seguro que quiere activar el servidor?")
                .setPositiveButton("Activar", (dialog, which) -> {
                    Intent intent = new Intent(this, ServidorService.class);
                    ContextCompat.startForegroundService(this, intent);
                    actualizarUiEncendido();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmarApagarServidor() {
        new AlertDialog.Builder(this)
                .setTitle("Desactivar servidor")
                .setMessage("¿Seguro que quiere desactivar el servidor?")
                .setPositiveButton("Desactivar", (dialog, which) -> {
                    // ★ Se cancela primero el watchdog: si no, a los pocos
                    // minutos volvería a levantar el servidor que el usuario
                    // acaba de apagar a propósito.
                    com.example.bufaloserver.servicio.WatchdogReceiver.cancelarProximoTick(this);
                    Intent intent = new Intent(this, ServidorService.class);
                    stopService(intent);
                    actualizarUiApagado();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void actualizarUiEncendido() {
        servidorEncendido = true;
        if (estadoUiActual == 1) return;
        estadoUiActual = 1;
        tvEstado.setText("🟢 Servidor activo");
        btnToggle.setText("Detener servidor");
        aplicarColorBoton(0xFFD32F2F);
    }

    private void actualizarUiIniciando() {
        servidorEncendido = true;
        if (estadoUiActual == 2) return;
        estadoUiActual = 2;
        tvEstado.setText("🟠 Servidor iniciando/reparando…");
        btnToggle.setText("Detener servidor");
        aplicarColorBoton(0xFFF57C00);
    }

    private void actualizarUiApagado() {
        servidorEncendido = false;
        if (estadoUiActual == 0) return;
        estadoUiActual = 0;
        tvEstado.setText("🔴 Servidor detenido");
        btnToggle.setText("Iniciar servidor");
        aplicarColorBoton(0xFF2E7D32);
        tvClientesConectados.setText("");
    }

    /**
     * Lee ServidorWebSocket.getCantidadConectados() a través de la instancia
     * estática de ServidorService (ver comentario ahí). Si el servicio no
     * está corriendo, o el WebSocketServer todavía no arrancó, simplemente
     * no muestra nada — no es un error, es el estado normal antes de tocar
     * "Iniciar servidor".
     */
    private void actualizarClientesConectados() {
        ServidorService servicio = ServidorService.getInstancia();
        if (servicio == null || servicio.getServidorWebSocket() == null) {
            tvClientesConectados.setText("");
            return;
        }
        int n = servicio.getServidorWebSocket().getCantidadConectados();
        tvClientesConectados.setText(n == 1
                ? "🔌 1 tablet conectada"
                : "🔌 " + n + " tablets conectadas");
    }

    private void aplicarColorBoton(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        btnToggle.setBackground(bg);
    }

    /** Actualiza red Wi-Fi + IP:puerto + QR — todo junto, porque los 3 dependen
     *  de la misma lectura de WifiManager. */
    private void actualizarInfoRed() {
        String ssid = obtenerSsid();
        String ip = obtenerIpLocal();
        String ipPuerto = ip + ":" + ServidorService.PUERTO_HTTP;

        tvRed.setText(ssid != null ? "📶 Red: " + ssid : "📶 Red: no se pudo detectar (¿wifi conectado?)");
        tvIpPuerto.setText(ipPuerto);
        // El QR solo se redibuja si la dirección cambió: así el refresco
        // periódico no repinta/parpadea la imagen cada 10 s sin motivo.
        if (!ipPuerto.equals(ultimaIpPuerto)) {
            ultimaIpPuerto = ipPuerto;
            generarQr(ipPuerto);
        }
    }

    private void generarQr(String contenido) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            int tamano = dp(196); // un poco menos que el ImageView (220dp) para el padding
            BitMatrix matriz = writer.encode(contenido, BarcodeFormat.QR_CODE, tamano, tamano);

            Bitmap bitmap = Bitmap.createBitmap(tamano, tamano, Bitmap.Config.RGB_565);
            for (int x = 0; x < tamano; x++) {
                for (int y = 0; y < tamano; y++) {
                    bitmap.setPixel(x, y, matriz.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            imgQr.setImageBitmap(bitmap);
        } catch (WriterException e) {
            // No debería pasar nunca con un string simple "ip:puerto" — si
            // llegara a fallar, se deja el ImageView vacío en vez de crashear;
            // el texto de IP:puerto arriba sigue sirviendo para configurar a mano.
        }
    }

    @Nullable
    private String obtenerSsid() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            String ssid = wifiManager.getConnectionInfo().getSSID();
            if (ssid == null || ssid.equals("<unknown ssid>") || ssid.equals("0x")) {
                return null; // sin permiso de ubicación concedido, o wifi desconectado
            }
            // Android devuelve el SSID entre comillas ("MiRed") — se las saco.
            return ssid.replace("\"", "");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ★ FIX IP — antes se usaba WifiManager.getConnectionInfo().getIpAddress(),
     * deprecado, que devuelve 0 en equipos/permisos donde no tiene visibilidad
     * de la conexión (el QR terminaba mostrando "192.168.x.x"). Ahora se lee
     * la dirección IPv4 real de las interfaces de red (wlan/eth/ap), que no
     * depende de ese permiso, y se deja WifiManager solo como último recurso.
     */
    private String obtenerIpLocal() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            String candidata = null;
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String nombre = ni.getName();
                boolean esWifi = nombre != null && (nombre.startsWith("wlan") || nombre.startsWith("eth")
                        || nombre.startsWith("ap") || nombre.startsWith("swlan"));
                for (java.net.InetAddress addr : java.util.Collections.list(ni.getInetAddresses())) {
                    if (addr == null || addr.isLoopbackAddress()
                            || !(addr instanceof java.net.Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("169.254.")) continue; // link-local, sin DHCP
                    if (esWifi) return ip;
                    if (candidata == null) candidata = ip;
                }
            }
            if (candidata != null) return candidata;
        } catch (Exception ignored) {
        }

        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            int ip = wifiManager.getConnectionInfo().getIpAddress();
            if (ip != 0) return Formatter.formatIpAddress(ip);
        } catch (Exception ignored) {
        }
        return "192.168.x.x"; // sin wifi conectado
    }

    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 201) actualizarInfoRed(); // el SSID puede estar disponible ahora
    }

    @Override
    protected void onStart() {
        super.onStart();
        handlerClientes.post(refrescarClientesRunnable);
        sincronizarUiConServicioReal(); // red de seguridad: refleja el estado real por si cambió mientras la pantalla no estaba visible
        actualizarVisibilidadBotonBateria(); // por si el usuario volvió de Ajustes habiendo aceptado
        refrescarEstadoLimpieza();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handlerClientes.removeCallbacks(refrescarClientesRunnable);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ★ NUEVO — Limpieza de datos antiguos (ver servicio/LimpiezaDatos)
    // Todo lo que toca Room corre en dbExecutor (nunca en el hilo de la UI).
    // ══════════════════════════════════════════════════════════════════════

    /** Línea de estado bajo el botón: última limpieza y tamaño de la base. */
    private void refrescarEstadoLimpieza() {
        if (tvLimpieza == null) return;
        com.example.bufaloserver.db.AppDatabaseServidor.dbExecutor.execute(() -> {
            String texto;
            try {
                org.json.JSONObject e = com.example.bufaloserver.servicio.LimpiezaDatos.estadoComoJson(this);
                long ultima = e.optLong("ultimaLimpiezaMs", 0);
                String tam = com.example.bufaloserver.servicio.LimpiezaDatos
                        .formatearBytes(e.optLong("tamanoBaseBytes", 0));
                if (e.optBoolean("primeraLimpiezaPendiente", true)) {
                    texto = "Nunca se limpió · Base: " + tam;
                } else {
                    String f = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
                            .format(new java.util.Date(ultima));
                    texto = "Última limpieza: " + f + " · Base: " + tam;
                }
            } catch (Exception ex) {
                texto = "";
            }
            final String t = texto;
            runOnUiThread(() -> { if (tvLimpieza != null) tvLimpieza.setText(t); });
        });
    }

    /** Paso 1: simula (no borra nada) y muestra qué se borraría. */
    private void abrirLimpiezaDatos() {
        Toast.makeText(this, "Calculando…", Toast.LENGTH_SHORT).show();
        com.example.bufaloserver.db.AppDatabaseServidor.dbExecutor.execute(() -> {
            try {
                final org.json.JSONObject estado = com.example.bufaloserver.servicio.LimpiezaDatos.estadoComoJson(this);
                final com.example.bufaloserver.servicio.LimpiezaDatos.Informe sim =
                        com.example.bufaloserver.servicio.LimpiezaDatos.simular(this);
                runOnUiThread(() -> mostrarDialogoSimulacion(estado, sim));
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo calcular: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void mostrarDialogoSimulacion(org.json.JSONObject estado,
                                          com.example.bufaloserver.servicio.LimpiezaDatos.Informe sim) {
        // isDestroyed(): si la tablet rotó mientras se calculaba, esta Activity ya no es válida
        // y mostrar un diálogo en ella lanza BadTokenException (crash).
        if (isFinishing() || isDestroyed()) return;
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("Se conservan el mes actual y los ")
          .append(com.example.bufaloserver.servicio.LimpiezaDatos.MESES_A_CONSERVAR - 1)
          .append(" anteriores (desde el ").append(fmt.format(new java.util.Date(sim.corteMs))).append(").\n\n");

        if (!sim.hayAlgo()) {
            sb.append("✅ No hay datos más antiguos que borrar.");
        } else {
            sb.append("Se borrarían:\n")
              .append("• ").append(sim.pedidos).append(" pedidos (").append(sim.itemsPedido).append(" líneas)\n")
              .append("• ").append(sim.gastosDiarios).append(" gastos diarios\n")
              .append("• ").append(sim.movimientosInventario).append(" movimientos de inventario\n")
              .append("• ").append(sim.auditoria).append(" registros de auditoría\n");
            if (!sim.mesesAResumir.isEmpty()) {
                sb.append("\nAntes se guarda un resumen de: ");
                for (int i = 0; i < sim.mesesAResumir.size(); i++) {
                    int[] ym = sim.mesesAResumir.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(String.format(java.util.Locale.US, "%02d/%d", ym[1], ym[0]));
                }
                sb.append(".");
            }
            sb.append("\n\nNo se tocan: productos, categorías, usuarios, gastos fijos ni pedidos pendientes.");
        }
        if (sim.pedidosNoCerradosViejos > 0) {
            sb.append("\n\n⚠️ Hay ").append(sim.pedidosNoCerradosViejos)
              .append(" pedido(s) viejo(s) sin cerrar (pendientes). NO se borran: revísalos.");
        }
        sb.append("\n\nBase actual: ")
          .append(com.example.bufaloserver.servicio.LimpiezaDatos.formatearBytes(sim.tamanoAntesBytes));
        if (estado.optBoolean("primeraLimpiezaPendiente", false)) {
            sb.append("\n\nℹ️ Es la PRIMERA limpieza: se hace a mano y borra de golpe todo lo anterior. ")
              .append("Después se hará sola, cada mes, la primera vez que el servidor esté encendido sin movimiento de pedidos.");
        }
        if (sim.hayAlgo()) {
            sb.append("\n\n📄 Antes de ejecutar: exporta desde Reportes el PDF de cada mes que quieras guardar.");
        } else if (estado.optBoolean("primeraLimpiezaPendiente", false)) {
            sb.append("\n\nSi activas la limpieza automática, desde ahora cada mes se borrará solo lo más antiguo ")
              .append("(después de guardar su resumen).");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("🧹 Limpieza de datos")
                .setMessage(sb.toString())
                .setNegativeButton("Cerrar", null);
        if (sim.hayAlgo()) {
            b.setPositiveButton("Ejecutar limpieza…", (d, w) -> pedirConfirmacionLimpieza(sim));
        } else if (estado.optBoolean("primeraLimpiezaPendiente", false)) {
            // Instalación nueva: todavía no hay nada viejo, pero sin esta primera limpieza manual
            // la automática NUNCA arranca. "Activar" no borra nada: solo la habilita.
            b.setPositiveButton("Activar limpieza automática", (d, w) -> ejecutarLimpiezaDatos());
        }
        b.show();
    }

    /** Paso 2: confirmación escrita (acción destructiva e irreversible). */
    private void pedirConfirmacionLimpieza(com.example.bufaloserver.servicio.LimpiezaDatos.Informe sim) {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(4));

        TextView aviso = new TextView(this);
        aviso.setText("Se borrarán " + sim.pedidos + " pedidos y los datos asociados. " +
                "Esto NO se puede deshacer.\n\nPara confirmar, escribe BORRAR:");
        aviso.setTextSize(13f);
        box.addView(aviso);

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("BORRAR");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(et);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("¿Confirmar limpieza?")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", null) // se asigna abajo para no cerrar con texto incorrecto
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!"BORRAR".equals(et.getText().toString().trim())) {
                et.setError("Escribe BORRAR para confirmar");
                return;
            }
            dlg.dismiss();
            ejecutarLimpiezaDatos();
        }));
        dlg.show();
    }

    private void ejecutarLimpiezaDatos() {
        final AlertDialog espera = new AlertDialog.Builder(this)
                .setTitle("Limpiando…")
                .setMessage("No cierres la app. Esto puede tardar unos segundos.")
                .setCancelable(false)
                .create();
        espera.show();

        com.example.bufaloserver.db.AppDatabaseServidor.dbExecutor.execute(() -> {
            // En la tablet del servidor no hay sesión de usuario: queda registrada como "Servidor".
            final com.example.bufaloserver.servicio.LimpiezaDatos.Informe inf =
                    com.example.bufaloserver.servicio.LimpiezaDatos.ejecutar(this, 0, "Servidor (tablet)", "administrador");
            runOnUiThread(() -> {
                // Si la Activity se destruyó (rotación) mientras limpiaba, la limpieza igual terminó
                // bien; solo se omite el diálogo (mostrarlo en una Activity muerta crashea).
                try { espera.dismiss(); } catch (Exception ignorada) { }
                if (isFinishing() || isDestroyed()) return;
                String msg;
                if (inf.error != null) {
                    msg = "❌ " + inf.error;
                } else {
                    msg = "✅ Limpieza terminada.\n\n" +
                            "• " + inf.pedidos + " pedidos borrados\n" +
                            "• " + inf.gastosDiarios + " gastos diarios\n" +
                            "• " + inf.movimientosInventario + " movimientos de inventario\n" +
                            "• " + inf.auditoria + " registros de auditoría\n\n" +
                            "Base: " + com.example.bufaloserver.servicio.LimpiezaDatos.formatearBytes(inf.tamanoAntesBytes) +
                            " → " + com.example.bufaloserver.servicio.LimpiezaDatos.formatearBytes(inf.tamanoDespuesBytes) +
                            (inf.vacuumOmitido ? "\n\n⚠️ No se pudo compactar el archivo (poco espacio o base en uso); " +
                                    "los datos sí se borraron." : "");
                }
                new AlertDialog.Builder(this).setTitle("Limpieza de datos").setMessage(msg)
                        .setPositiveButton("OK", null).show();
                refrescarEstadoLimpieza();
            });
        });
    }
}
