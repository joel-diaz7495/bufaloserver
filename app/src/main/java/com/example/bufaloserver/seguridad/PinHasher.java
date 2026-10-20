package com.example.bufaloserver.seguridad;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * ★ NUEVO — hasheo del PIN de acceso (Usuario.pinHash/pinSalt), reemplaza el
 * texto plano que se guardaba antes en Usuario.pin.
 *
 * Por qué importa: antes cualquiera con acceso al archivo de la base (un
 * backup, un dispositivo físico robado, un rooteo) veía el PIN de los 4
 * usuarios/empleados en texto plano, sin ningún esfuerzo. El login en sí no
 * quedaba más expuesto por eso — ServidorHttp.manejarLogin exige además un
 * deviceToken de un dispositivo YA EMPAREJADO como ese usuario en particular,
 * así que un PIN filtrado sin el dispositivo emparejado no alcanza para
 * entrar — pero la base de datos en reposo sí quedaba expuesta.
 *
 * PBKDF2WithHmacSHA256 en vez de SHA-256/MD5 directo porque PBKDF2 está
 * pensado para ser LENTO a propósito (con muchas iteraciones), lo que
 * encarece un ataque de fuerza bruta offline contra la base robada. Dicho
 * eso: con un PIN de 4 dígitos numéricos el espacio de búsqueda es apenas
 * 10.000 valores — ni el mejor hash del mundo lo vuelve "seguro" contra
 * alguien que se toma el trabajo de probar los 10.000 offline. La ganancia
 * real de esto es tapar la exposición más barata y tonta (leer el archivo
 * y ver el PIN a simple vista), no convertir un PIN de 4 dígitos en una
 * contraseña fuerte — eso requeriría cambiar el esquema de autenticación
 * en sí (PINs más largos, límite de intentos, etc.), que queda fuera de
 * este fix.
 *
 * PBKDF2WithHmacSHA256 está disponible desde API 26 — coincide justo con
 * el minSdk de este proyecto (ver build.gradle.kts, comentario sobre la
 * Huawei AGS-L03), así que no hace falta ninguna librería externa ni un
 * fallback para SDKs viejos.
 */
public final class PinHasher {

    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";

    // 120.000 iteraciones: un valor típico recomendado para PBKDF2-SHA256 en
    // 2024-2026. En el hardware más flojo que corre esta app (la Huawei
    // AGS-L03, Android 8), un login tarda un extra de algunas decenas de
    // milisegundos por esto — imperceptible para el usuario (el login ya
    // implica ida y vuelta de red), y sigue siendo rápido porque esto corre
    // UNA vez por login, no en un loop.
    // ★ FIX LOGIN LENTO — antes 120_000 para todo. En la Huawei AGS-L03
    // (Android 8, PBKDF2 en Java puro, sin JIT caliente) una sola
    // verificación podía tardar segundos y pasar los 5 s de timeout del
    // cliente. Los hashes NUEVOS usan ITERACIONES (10_000); los viejos
    // guardados sin prefijo se siguen verificando con ITERACIONES_LEGADO
    // y se re-hashean solos en el primer login correcto (ver
    // ServidorHttp.manejarLogin). Formato nuevo: "<iteraciones>$<base64>".
    // Con un PIN de 4 dígitos el espacio es 10.000 valores: la diferencia
    // de seguridad real entre 10k y 120k iteraciones es despreciable; el
    // login ya exige además un dispositivo emparejado.
    private static final int ITERACIONES = 10_000;
    private static final int ITERACIONES_LEGADO = 120_000;
    private static final int LONGITUD_CLAVE_BITS = 256;
    private static final int LONGITUD_SALT_BYTES = 16;

    // ★ MEJORA VELOCIDAD — caché EN MEMORIA de PINs ya verificados. Después
    // de un login correcto se guarda HMAC-SHA256(claveDelProceso, hash|pin)
    // por usuario; los logins siguientes con el mismo PIN comparan eso
    // (microsegundos) en vez de repetir PBKDF2. La clave es aleatoria y
    // solo vive en este proceso: no se guarda en disco, y si el PIN cambia
    // (cambia el hash guardado) la entrada deja de coincidir sola. Un PIN
    // incorrecto nunca entra a la caché y siempre paga la verificación completa.
    private static final byte[] CLAVE_CACHE = new byte[32];
    private static final ConcurrentHashMap<Long, String> CACHE_VERIFICADOS = new ConcurrentHashMap<>();
    static { new SecureRandom().nextBytes(CLAVE_CACHE); }

    private PinHasher() {}

    private static String huella(String hashGuardado, String pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CLAVE_CACHE, "HmacSHA256"));
            byte[] r = mac.doFinal((hashGuardado + "|" + (pin != null ? pin : "")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Igual que verificar(), pero usa/llena la caché en memoria. Devuelve en
     *  fastPath[0] si salió de la caché (para el log). */
    public static boolean verificarConCache(long usuarioId, String pin, String salt, String hashGuardado, boolean[] fastPath) {
        if (hashGuardado == null) return false;
        String huella = huella(hashGuardado, pin);
        String guardada = CACHE_VERIFICADOS.get(usuarioId);
        if (huella != null && guardada != null && constantTimeEquals(huella, guardada)) {
            if (fastPath != null) fastPath[0] = true;
            return true;
        }
        boolean ok = verificar(pin, salt, hashGuardado);
        if (ok && huella != null) CACHE_VERIFICADOS.put(usuarioId, huella);
        return ok;
    }

    /** Llamar en un hilo de fondo al arrancar el servidor: carga el proveedor
     *  PBKDF2/HMAC y deja el JIT caliente, para que el primer login real no
     *  pague ese arranque en frío. */
    public static void precalentar() {
        try {
            hashear("0000", generarSalt());
            huella("precalentar", "0000");
        } catch (Exception ignored) {}
    }

    /** Genera una sal nueva, aleatoria, para UN usuario. Nunca se reutiliza entre usuarios. */
    public static String generarSalt() {
        byte[] salt = new byte[LONGITUD_SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** Hashea un PIN contra una sal ya existente con las iteraciones actuales.
     *  Devuelve "<iteraciones>$<base64>". Determinístico: mismo PIN + misma sal = mismo hash. */
    public static String hashear(String pin, String saltBase64) {
        return ITERACIONES + "$" + calcular(pin, saltBase64, ITERACIONES);
    }

    private static String calcular(String pin, String saltBase64, int iteraciones) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            PBEKeySpec spec = new PBEKeySpec(
                    (pin != null ? pin : "").toCharArray(), salt, iteraciones, LONGITUD_CLAVE_BITS);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITMO);
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("No se pudo hashear el PIN (" + ALGORITMO + " no disponible)", e);
        }
    }

    /** Iteraciones con las que se calculó un hash guardado (sin prefijo = formato viejo). */
    private static int iteracionesDe(String hashGuardado) {
        int i = hashGuardado.indexOf('$');
        if (i < 0) return ITERACIONES_LEGADO;
        try { return Integer.parseInt(hashGuardado.substring(0, i)); }
        catch (NumberFormatException e) { return ITERACIONES_LEGADO; }
    }

    private static String soloHash(String hashGuardado) {
        int i = hashGuardado.indexOf('$');
        return i < 0 ? hashGuardado : hashGuardado.substring(i + 1);
    }

    /** true si el hash guardado se calculó con un costo distinto al actual (hay que re-hashear). */
    public static boolean necesitaActualizar(String hashGuardado) {
        return hashGuardado == null || iteracionesDe(hashGuardado) != ITERACIONES;
    }

    /**
     * Compara un PIN candidato (lo que tipeó el usuario al loguearse) contra
     * el hash+sal guardados. Comparación en tiempo constante sobre el
     * resultado final — no evita que alguien mida CUÁNTO TARDA en calcular
     * el hash (eso depende solo de ITERACIONES, siempre igual), pero sí
     * evita la fuga más básica de comparar el string resultante char por
     * char con early-exit (String.equals).
     */
    public static boolean verificar(String pinCandidato, String saltBase64, String hashEsperadoBase64) {
        if (saltBase64 == null || hashEsperadoBase64 == null) return false;
        // Se recalcula con las MISMAS iteraciones con que se guardó, así
        // los hashes viejos (120k, sin prefijo) siguen funcionando.
        String hashCandidato = calcular(pinCandidato, saltBase64, iteracionesDe(hashEsperadoBase64));
        return constantTimeEquals(hashCandidato, soloHash(hashEsperadoBase64));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diferencia = 0;
        for (int i = 0; i < a.length(); i++) {
            diferencia |= a.charAt(i) ^ b.charAt(i);
        }
        return diferencia == 0;
    }
}
