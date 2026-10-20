package com.example.bufaloserver.servicio;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bitácora simple en disco de los eventos de vida del servidor (arranca,
 * se cae, lo revive el watchdog, reinicio de la tablet, etc.) — para poder
 * confirmar en el campo, sin necesidad de una compu con ADB, si el
 * servidor se mantuvo corriendo toda la noche o se cayó y a qué hora. Se ve
 * desde el botón "📋 Ver registro" en EstadoServidorActivity.
 *
 * Deliberadamente un archivo de texto plano, sin Room ni nada — es
 * diagnóstico, no dato del negocio, y conviene que se pueda leer aunque la
 * app tenga bugs en cualquier otra parte (Room, red, etc.).
 */
public class DiagnosticoLog {

    private static final String TAG = "DiagnosticoLog";
    private static final String NOMBRE_ARCHIVO = "diagnostico_servidor.log";
    /** ~200KB de tope — de sobra para semanas de eventos, sin crecer sin límite. */
    private static final long TAMANO_MAX_BYTES = 200_000;
    private static final SimpleDateFormat FORMATO =
            new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());

    public static synchronized void registrar(Context context, String mensaje) {
        try {
            File archivo = new File(context.getFilesDir(), NOMBRE_ARCHIVO);
            rotarSiHaceFalta(archivo);
            String linea = FORMATO.format(new Date()) + " — " + mensaje + "\n";
            try (FileWriter fw = new FileWriter(archivo, true)) {
                fw.write(linea);
            }
        } catch (IOException e) {
            Log.e(TAG, "No se pudo escribir el log de diagnóstico", e);
        }
    }

    /** Si el archivo ya es muy grande, lo trunca dejando solo la mitad final
     *  (los eventos más recientes) — así nunca crece sin límite. */
    private static void rotarSiHaceFalta(File archivo) {
        if (!archivo.exists() || archivo.length() < TAMANO_MAX_BYTES) return;
        try {
            List<String> lineas = leerTodasLasLineas(archivo);
            int desde = lineas.size() / 2;
            try (FileWriter fw = new FileWriter(archivo, false)) {
                for (int i = desde; i < lineas.size(); i++) {
                    fw.write(lineas.get(i));
                    fw.write("\n");
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "No se pudo rotar el log de diagnóstico", e);
        }
    }

    private static List<String> leerTodasLasLineas(File archivo) throws IOException {
        List<String> lineas = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) lineas.add(linea);
        }
        return lineas;
    }
}