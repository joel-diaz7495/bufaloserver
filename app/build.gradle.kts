plugins {
    id("com.android.application")
}
android {
    namespace = "com.example.bufaloserver"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.bufaloserver"
        // minSdk = 26 (Android 8) porque la Huawei AGS-L03 corre ese SO —
        // nada de APIs nuevas que la dejen afuera.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // ★ NUEVO — el template de Android Studio genera
        // ExampleInstrumentedTest.java usando este runner por defecto; sin
        // declararlo acá algunos setups también fallan al resolver
        // androidx.test en instrumentación. No afecta nada del APK final,
        // solo cómo se corren los tests instrumentados.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ★ NUEVO — le dice al annotation processor de Room DÓNDE volcar el
        // JSON del esquema de cada versión de la base (1.json, 2.json, ...,
        // 12.json), ahora que AppDatabaseServidor tiene exportSchema = true.
        // Sin esto, exportSchema=true tira un warning en cada build ("schema
        // export directory not provided") y no genera nada. Esa carpeta
        // conviene versionarla en git — es la que después usa
        // MigrationTestHelper para poder testear las migraciones de verdad
        // en vez de confiar a ojo en que el SQL escrito a mano está bien.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ★ NUEVO — expone la carpeta "schemas/" (generada arriba) como un
    // "asset" del APK de androidTest, que es donde MigrationTestHelper la
    // busca en tiempo de ejecución del test instrumentado. Sin esta línea,
    // el JSON se genera igual en disco, pero un test instrumentado que
    // corra en un dispositivo/emulador no puede leerlo (no viaja dentro del
    // APK de test).
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}
dependencies {
    // ANDROIDX — mínimo indispensable, nada de Compose pesado: el servidor
    // casi no tiene UI (una pantalla de estado + QR, nada más).
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0") // requerido por el tema Theme.MaterialComponents.* que deja Android Studio por defecto en themes.xml — sin esto falla el linking de recursos (colorPrimaryVariant, etc.)
    implementation("androidx.core:core:1.12.0") // NotificationCompat para el Foreground Service
    implementation("androidx.activity:activity:1.8.2") // registerForActivityResult (permiso de ubicación)
    // ROOM — mismo motor SQLite que ya conocés del cliente, mismo patrón.
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    // Los DAOs reusados del cliente devuelven LiveData<List<...>> en varios
    // métodos (pensado para el KDS, que se refresca con un listener/LiveData
    // en vez de red) — hace falta esto para que Room los pueda generar.
    implementation("androidx.lifecycle:lifecycle-livedata-core:2.7.0")
    // HTTP — NanoHTTPD, liviano a propósito (una sola clase, sin framework atrás).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // WEBSOCKET — misma librería que el cliente, acá usamos WebSocketServer en
    // vez de WebSocketClient.
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    // QR — para que la pantalla de estado muestre el ip:puerto como código QR
    // (generarlo es más simple que leerlo: no hace falta la parte de cámara
    // de ZXing, solo su generador de códigos).
    implementation("com.google.zxing:core:3.5.3")

    // ★ NUEVO — Room 2.6.1 necesita esto para MigrationTestHelper (la clase
    // que corre las migraciones a mano contra el schema exportado y avisa
    // si algo no coincide). Va como androidTestImplementation porque solo
    // se usa desde un test instrumentado, nunca en el APK real.
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    // esto es lo que faltaba y causaba el error. El proyecto trae
    // de fábrica app/src/androidTest/java/.../ExampleInstrumentedTest.java,
    // que importa androidx.test.platform.app.InstrumentationRegistry y usa
    // androidx.test.ext.junit.runners.AndroidJUnit4 — esas clases viven en
    // las librerías de abajo, y sin declararlas como androidTestImplementation
    // no existen en el classpath de androidTest, aunque el resto del proyecto
    // compile bien. androidx.test.ext:junit ya trae androidx.test:core y
    // androidx.test:runner como dependencia transitiva (de ahí sale
    // InstrumentationRegistry).
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Tests unitarios normales (app/src/test/...) — probablemente también
    // falte si hay un ExampleUnitTest.java de fábrica. No hace daño tenerla
    // aunque hoy no haya ningún test unitario real todavía.
    testImplementation("junit:junit:4.13.2")
}