package com.example.bufaloserver.print;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Set;

/**
 * Pantalla de configuración de la impresora térmica DEL SERVIDOR (bufaloserver).
 *
 * Equivalente a PrinterConfigActivity del cliente, pero:
 *  - Construida 100% por código (igual que EstadoServidorActivity), porque
 *    este proyecto no usa layouts XML.
 *  - No depende de R — así se puede pegar en el proyecto sin tocar res/.
 *  - No tiene botón "Imprimir en cocina" obligatorio para guardar nada (acá
 *    no hay pantalla de venta): sirve para elegir la impresora Bluetooth
 *    emparejada y el modo (Impresora/Registro), igual que en el cliente.
 *
 * Se abre desde EstadoServidorActivity con un botón "🖨️ Configurar impresora".
 */
public class PrinterConfigActivityServidor extends AppCompatActivity implements PrinterManager.StatusListener {

    private static final int REQ_BLUETOOTH_PERMS = 601;

    private PrinterManager printerManager;

    private TextView tvEstadoConexion;
    private AppCompatButton btnModoImpresora, btnModoRegistro;
    private TextView tvModoExplicacion;
    private TextView tvSeccionImpresora;
    private AppCompatButton btnPedirPermisos, btnRefrescarLista, btnImprimirPrueba, btnDesconectar, btnVolver;
    private LinearLayout containerDispositivos;
    private TextView tvMacGuardada;

    private String macSeleccionada;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        printerManager = PrinterManager.get(this);
        macSeleccionada = PrinterPrefs.getMac(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView tvTitulo = new TextView(this);
        tvTitulo.setText("Impresora del servidor");
        tvTitulo.setTextSize(20f);
        tvTitulo.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitulo);

        TextView tvSub = new TextView(this);
        tvSub.setText("Esta es la impresora que se usa para todos los pedidos que llegan de las tablets conectadas.");
        tvSub.setTextSize(12f);
        tvSub.setTextColor(0xFF888888);
        tvSub.setPadding(0, dp(4), 0, dp(16));
        root.addView(tvSub);

        tvEstadoConexion = new TextView(this);
        tvEstadoConexion.setTextSize(14f);
        tvEstadoConexion.setTypeface(null, Typeface.BOLD);
        tvEstadoConexion.setPadding(0, 0, 0, dp(16));
        root.addView(tvEstadoConexion);

        // ── Modo ─────────────────────────────────────────────────────────────
        LinearLayout filaModo = new LinearLayout(this);
        filaModo.setOrientation(LinearLayout.HORIZONTAL);
        btnModoImpresora = buildBtn("🖨️ Modo Impresora");
        btnModoRegistro  = buildBtn("📋 Modo Registro");
        LinearLayout.LayoutParams lpModo = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpModo.setMargins(0, 0, dp(8), 0);
        btnModoImpresora.setLayoutParams(lpModo);
        btnModoRegistro.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        filaModo.addView(btnModoImpresora);
        filaModo.addView(btnModoRegistro);
        root.addView(filaModo);

        tvModoExplicacion = new TextView(this);
        tvModoExplicacion.setTextSize(12f);
        tvModoExplicacion.setTextColor(0xFF888888);
        tvModoExplicacion.setPadding(0, dp(8), 0, dp(16));
        root.addView(tvModoExplicacion);

        tvSeccionImpresora = new TextView(this);
        tvSeccionImpresora.setText("Dispositivos Bluetooth emparejados");
        tvSeccionImpresora.setTextSize(13f);
        tvSeccionImpresora.setTypeface(null, Typeface.BOLD);
        tvSeccionImpresora.setPadding(0, dp(8), 0, dp(8));
        root.addView(tvSeccionImpresora);

        btnPedirPermisos = buildBtn("Conceder permisos Bluetooth");
        btnPedirPermisos.setVisibility(View.GONE);
        root.addView(btnPedirPermisos);

        btnRefrescarLista = buildBtn("🔄 Refrescar lista");
        LinearLayout.LayoutParams lpRefrescar = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpRefrescar.topMargin = dp(4);
        lpRefrescar.bottomMargin = dp(8);
        btnRefrescarLista.setLayoutParams(lpRefrescar);
        root.addView(btnRefrescarLista);

        containerDispositivos = new LinearLayout(this);
        containerDispositivos.setOrientation(LinearLayout.VERTICAL);
        root.addView(containerDispositivos);

        tvMacGuardada = new TextView(this);
        tvMacGuardada.setTextSize(12f);
        tvMacGuardada.setTextColor(0xFF555555);
        tvMacGuardada.setPadding(0, dp(12), 0, dp(4));
        root.addView(tvMacGuardada);

        btnImprimirPrueba = buildBtn("🧾 Imprimir ticket de prueba");
        LinearLayout.LayoutParams lpPrueba = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpPrueba.topMargin = dp(12);
        btnImprimirPrueba.setLayoutParams(lpPrueba);
        root.addView(btnImprimirPrueba);

        btnDesconectar = buildBtn("Desconectar impresora");
        LinearLayout.LayoutParams lpDesc = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpDesc.topMargin = dp(8);
        btnDesconectar.setLayoutParams(lpDesc);
        root.addView(btnDesconectar);

        btnVolver = buildBtn("← Volver");
        LinearLayout.LayoutParams lpVolver = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpVolver.topMargin = dp(24);
        btnVolver.setLayoutParams(lpVolver);
        root.addView(btnVolver);

        scroll.addView(root);
        setContentView(scroll);

        btnModoImpresora.setOnClickListener(v -> cambiarModo(PrinterPrefs.MODO_IMPRESORA));
        btnModoRegistro.setOnClickListener(v -> cambiarModo(PrinterPrefs.MODO_REGISTRO));
        btnPedirPermisos.setOnClickListener(v -> pedirPermisosBluetooth());
        btnRefrescarLista.setOnClickListener(v -> refrescarDispositivos());
        btnImprimirPrueba.setOnClickListener(v -> imprimirTicketDePrueba());
        btnDesconectar.setOnClickListener(v -> {
            printerManager.desconectar();
            Toast.makeText(this, "Desconectado", Toast.LENGTH_SHORT).show();
        });
        btnVolver.setOnClickListener(v -> finish());

        actualizarUiModo();
        actualizarMacGuardadaLabel();

        if (faltanPermisos()) {
            btnPedirPermisos.setVisibility(View.VISIBLE);
        } else {
            refrescarDispositivos();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        printerManager.addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        printerManager.removeListener(this);
    }

    // ── Modo ─────────────────────────────────────────────────────────────────

    private void cambiarModo(String modo) {
        PrinterPrefs.setModo(this, modo);
        actualizarUiModo();
        if (PrinterPrefs.MODO_IMPRESORA.equals(modo)) {
            String mac = PrinterPrefs.getMac(this);
            if (mac != null) printerManager.iniciarConexionPersistente(mac);
        } else {
            printerManager.detenerConexionPersistente();
        }
    }

    private void actualizarUiModo() {
        boolean esImpresora = PrinterPrefs.esModoImpresora(this);
        btnModoImpresora.setAlpha(esImpresora ? 1f : 0.45f);
        btnModoRegistro.setAlpha(esImpresora ? 0.45f : 1f);
        tvModoExplicacion.setText(esImpresora
                ? "En este modo, cada pedido nuevo que llegue de una tablet se imprime automáticamente en esta impresora."
                : "En este modo los pedidos se guardan igual, pero el servidor nunca intenta imprimir nada.");
        int visibilidad = esImpresora ? View.VISIBLE : View.GONE;
        tvSeccionImpresora.setVisibility(visibilidad);
        btnRefrescarLista.setVisibility(visibilidad);
        containerDispositivos.setVisibility(visibilidad);
        tvMacGuardada.setVisibility(visibilidad);
        btnImprimirPrueba.setVisibility(visibilidad);
        btnDesconectar.setVisibility(visibilidad);
    }

    // ── Permisos ─────────────────────────────────────────────────────────────

    private boolean faltanPermisos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private void pedirPermisosBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, REQ_BLUETOOTH_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLUETOOTH_PERMS) {
            if (!faltanPermisos()) {
                btnPedirPermisos.setVisibility(View.GONE);
                refrescarDispositivos();
            } else {
                Toast.makeText(this, "Sin estos permisos no se puede usar la impresora Bluetooth", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── Lista de dispositivos emparejados ──────────────────────────────────

    private void refrescarDispositivos() {
        containerDispositivos.removeAllViews();
        if (faltanPermisos()) {
            btnPedirPermisos.setVisibility(View.VISIBLE);
            return;
        }
        if (!printerManager.bluetoothDisponibleYEncendido()) {
            TextView tv = new TextView(this);
            tv.setText("Bluetooth apagado. Actívalo desde Ajustes y vuelve a esta pantalla.");
            tv.setTextSize(12f);
            tv.setTextColor(0xFFC8102E);
            containerDispositivos.addView(tv);
            return;
        }

        Set<BluetoothDevice> pareados = printerManager.getDispositivosEmparejados();
        if (pareados.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("No hay dispositivos emparejados. Empareja la impresora desde Ajustes > Bluetooth de este celular/tablet (clave típica: 0000) y vuelve aquí.");
            tv.setTextSize(12f);
            tv.setTextColor(0xFF888888);
            containerDispositivos.addView(tv);
            return;
        }

        for (BluetoothDevice device : new ArrayList<>(pareados)) {
            containerDispositivos.addView(buildDispositivoRow(device));
        }
    }

    @SuppressWarnings("MissingPermission")
    private LinearLayout buildDispositivoRow(BluetoothDevice device) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        boolean esLaGuardada = device.getAddress().equals(macSeleccionada);
        bg.setColor(esLaGuardada ? 0xFFFFEEF1 : 0xFFF7F7F7);
        bg.setCornerRadius(dp(8));
        if (esLaGuardada) bg.setStroke(dp(1), 0xFFC8102E);
        row.setBackground(bg);
        LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowP.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(rowP);

        LinearLayout textos = new LinearLayout(this);
        textos.setOrientation(LinearLayout.VERTICAL);
        textos.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvNombre = new TextView(this);
        String nombre = device.getName() != null ? device.getName() : "(sin nombre)";
        tvNombre.setText((esLaGuardada ? "✅ " : "") + nombre);
        tvNombre.setTextSize(13f);
        tvNombre.setTypeface(null, Typeface.BOLD);
        tvNombre.setTextColor(0xFF222222);
        textos.addView(tvNombre);

        TextView tvMac = new TextView(this);
        tvMac.setText(device.getAddress());
        tvMac.setTextSize(11f);
        tvMac.setTextColor(0xFF888888);
        textos.addView(tvMac);

        row.addView(textos);

        AppCompatButton btnElegir = new AppCompatButton(this);
        btnElegir.setText(esLaGuardada ? "Reconectar" : "Elegir");
        btnElegir.setTextSize(11f);
        btnElegir.setAllCaps(false);
        btnElegir.setOnClickListener(v -> conectarA(device));
        row.addView(btnElegir);

        return row;
    }

    @SuppressWarnings("MissingPermission")
    private void conectarA(BluetoothDevice device) {
        Toast.makeText(this, "Conectando con " + device.getName() + "...", Toast.LENGTH_SHORT).show();
        printerManager.conectar(device.getAddress(), new PrinterManager.ConnectCallback() {
            @Override
            public void onConectado() {
                macSeleccionada = device.getAddress();
                PrinterPrefs.setMac(PrinterConfigActivityServidor.this, macSeleccionada);
                actualizarMacGuardadaLabel();
                refrescarDispositivos();
                if (PrinterPrefs.esModoImpresora(PrinterConfigActivityServidor.this)) {
                    printerManager.iniciarConexionPersistente(macSeleccionada);
                }
                Toast.makeText(PrinterConfigActivityServidor.this, "✅ Impresora conectada", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String motivo) {
                Toast.makeText(PrinterConfigActivityServidor.this, "❌ " + motivo, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void actualizarMacGuardadaLabel() {
        String mac = PrinterPrefs.getMac(this);
        tvMacGuardada.setText(mac != null
                ? "Impresora guardada: " + mac
                : "Todavía no hay una impresora guardada.");
    }

    // ── Ticket de prueba ─────────────────────────────────────────────────────

    private void imprimirTicketDePrueba() {
        TicketData data = new TicketData();
        data.numeroOrden = 0;
        data.cliente = "Prueba";
        data.estado = "pagado";
        data.metodoPago = "efectivo";
        data.items = new ArrayList<>();
        data.subtotal = 0;
        data.total = 0;

        byte[] bytes = EscPosBuilder.build(data);
        printerManager.imprimir(bytes, new PrinterManager.PrintCallback() {
            @Override
            public void onImpreso() {
                Toast.makeText(PrinterConfigActivityServidor.this, "✅ Ticket de prueba enviado", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String motivo) {
                Toast.makeText(PrinterConfigActivityServidor.this, "❌ " + motivo, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ── PrinterManager.StatusListener ───────────────────────────────────────

    @Override
    public void onStatusChanged(boolean conectado, boolean intentandoConectar) {
        if (conectado) {
            tvEstadoConexion.setText("● Impresora conectada");
            tvEstadoConexion.setTextColor(0xFF2E7D32);
        } else if (intentandoConectar) {
            tvEstadoConexion.setText("◐ Conectando...");
            tvEstadoConexion.setTextColor(0xFFAA8800);
        } else {
            tvEstadoConexion.setText("○ Sin conectar");
            tvEstadoConexion.setTextColor(0xFF888888);
        }
    }

    // ── Helpers de UI ────────────────────────────────────────────────────────

    private AppCompatButton buildBtn(String texto) {
        AppCompatButton btn = new AppCompatButton(this);
        btn.setText(texto);
        btn.setAllCaps(false);
        btn.setTextSize(13f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFEFEFEF);
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        btn.setPadding(dp(12), dp(10), dp(12), dp(10));
        return btn;
    }

    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }
}
