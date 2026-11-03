# Cuestionario de auditoría y verificación de sistemas

**Proyecto:** Búfalo Server — servidor local para punto de venta (Android).

---

## 1. Arquitectura y Gestión de Código

**1. ¿Qué arquitectura o patrón de diseño utiliza el sistema? Justifique su elección.**

Implementé el sistema como un monolito modular dentro de una sola aplicación Android. El servidor HTTP y el WebSocket conviven en el mismo proceso, y la información se guarda localmente con Room/SQLite. Separé el código por responsabilidades: servidor HTTP, WebSocket, base de datos, impresión, seguridad y servicio en segundo plano. Elegí este enfoque porque el sistema debe funcionar sin internet en una tablet de 2 GB de RAM; usar microservicios o un servidor externo agregaría infraestructura y puntos de falla sin un beneficio real para un solo local.

**2. Ante cambios o modificaciones estructurales, ¿la arquitectura permite escalar de forma modular sin romper lo que ya funciona?**

Sí, en parte. La base de datos está versionada con migraciones, así que puedo agregar tablas o columnas sin perder lo existente, y los endpoints nuevos se suman sin modificar los anteriores. El cliente consume un contrato HTTP estable. Lo que todavía me cuesta es el mantenimiento del archivo principal del servidor, donde se concentran muchas rutas; mi siguiente paso es separarlo en módulos por dominio para que cada cambio quede aislado.

**3. ¿Tienen integrado un gestor de control de versiones? ¿Cómo manejan los commits y la resolución de conflictos?**

Sí, uso Git. Hoy el repositorio tiene un commit inicial de respaldo y varios archivos nuevos sin confirmar, así que todavía no tengo un historial que muestre el proceso completo. Mi plan es trabajar con una rama por funcionalidad, commits descriptivos y revisión antes de integrar a la rama principal. Al ser un proyecto individual, los conflictos se resuelven comparando los cambios y probando la compilación antes de confirmar.

**4. ¿Se cumple el principio DRY en la lógica de negocio y en la interfaz?**

Lo apliqué parcialmente. Reutilizo funciones para las respuestas JSON, la lectura del cuerpo de las peticiones, la conversión de entidades a JSON y la validación de permisos por rol. Sin embargo, todavía hay repeticiones en los controles de sesión y en algunos bloques de error, y varias rutas de eliminación no pasan por una validación común. Reconozco que no es un cumplimiento estricto y forma parte de la refactorización pendiente.

---

## 2. Base de Datos y Almacenamiento

**1. ¿Qué motor de base de datos se está utilizando y por qué es el más adecuado?**

Uso SQLite a través de Room en la tablet. Es el motor adecuado porque está integrado en Android, no necesita un servidor aparte, funciona sin internet, soporta transacciones y mantiene la integridad referencial con muy poco consumo de recursos. Para la parte en la nube tengo preparado PostgreSQL con Supabase, orientado al respaldo y a los reportes consolidados de varias sucursales; esa integración todavía no está activa en la aplicación.

**2. ¿Qué volumen de archivos físicos proyectan que el sistema almacenará a corto y largo plazo?**

El servidor no guarda imágenes ni documentos: solo registros en la base de datos. Estimando 1 a 4 KB por pedido con sus ítems, y entre 200 y 500 pedidos por día, el crecimiento sería de aproximadamente 0,2 a 2 MB por día y de 6 a 60 MB por mes. Como el sistema conserva tres meses y luego resume y limpia los datos antiguos, el tamaño esperado en la tablet es de unos 20 a 180 MB. Los tickets y reportes se generan en el momento y no se almacenan como archivos en el servidor.

**3. ¿Dónde se alojarán esos archivos?**

La base local queda en el almacenamiento privado de la aplicación, en el archivo `bufaloserver.db` junto con su archivo temporal de escritura. En la nube, los registros se guardan en PostgreSQL mediante Supabase. No uso un servicio externo de archivos ni un bucket S3, porque el sistema no almacena imágenes ni documentos. Los PDF que exporta el usuario se guardan desde la aplicación cliente en el almacenamiento del dispositivo.

**4. ¿Cómo resuelve el sistema la concurrencia si varios usuarios intentan modificar la misma información al mismo tiempo?**

Cada petición HTTP se atiende en su propio hilo, y SQLite serializa las escrituras. Para el número de orden usé un bloqueo que cubre tanto el cálculo del siguiente número como la inserción, y las operaciones que tocan varias tablas se ejecutan dentro de una transacción. También agregué un identificador único por pedido para que un reintento no cree un duplicado. Lo que todavía no tengo es un control de versión por fila: si dos usuarios editan el mismo registro a la vez, prevalece el último cambio.

---

## 3. Despliegue, Entorno y Mantenimiento

**1. ¿Dónde piensan desplegar el sistema para su paso a producción?**

El sistema se despliega en el propio local, sobre una tablet Android que actúa como servidor. Los celulares y las demás tablets se conectan por Wi-Fi a la dirección IP de esa tablet. La instalación se hace con el APK, sin tienda de aplicaciones. La nube de Supabase es opcional y se usa para respaldo y reportes, no para atender las ventas del día a día.

**2. ¿Cuáles son los requerimientos técnicos mínimos del lugar o infraestructura donde se desplegará?**

La tablet servidor necesita Android 8 o superior, al menos 2 GB de RAM, alrededor de 500 MB a 1 GB de espacio libre y conexión Wi-Fi. Los dispositivos clientes también necesitan Android 8 o superior y estar en la misma red. Se recomienda que el router reserve una IP fija para el servidor y que permita la comunicación en los puertos 8080 y 8081. Para compilar se necesita JDK 11, Android SDK 34 y Gradle. No hace falta un servidor Linux ni una base de datos externa.

**3. ¿El sistema requerirá mantenimiento constante debido a actualizaciones de librerías, frameworks o motores de base de datos?**

Sí. Las dependencias están fijadas en versiones concretas y el proyecto apunta a Android 14, así que los cambios de Android en permisos, servicios en segundo plano y batería obligan a revisar la aplicación cada cierto tiempo. Lo mismo ocurre con las actualizaciones de Room y de Supabase. Todavía no tengo integración continua ni actualización automática de dependencias, por lo que el mantenimiento se planifica con revisiones periódicas y pruebas antes de cada versión.

**4. ¿Cómo está planificado el flujo para subir actualizaciones a producción sin dejar el sistema offline por horas?**

Por ahora la actualización es manual: compilo el APK, detengo el servicio, instalo la nueva versión y lo vuelvo a iniciar. El sistema queda fuera de línea solo unos minutos, y las migraciones de la base se aplican automáticamente al abrirla. No tengo implementado un mecanismo de actualización sin interrupción; como mejora futura podría usar actualización remota o gestión de dispositivos, y para la nube ya se pueden aplicar migraciones y desplegar funciones sin detener el servidor local.

---

## 4. Rendimiento, Escalabilidad y Usuarios

**1. ¿Cuántas personas estiman que utilizarán el sistema de manera simultánea en horas pico?**

Para un solo local estimo una tablet servidor y entre tres y ocho dispositivos clientes, con unas cinco a diez conexiones simultáneas en los momentos de mayor demanda, como el almuerzo o la cena. Es una estimación de diseño; todavía no hice una prueba de carga con usuarios reales, así que ese dato hay que validarlo.

**2. Si la carga de usuarios se triplica repentinamente, ¿el sistema tiene la capacidad de escalar?**

No de forma horizontal, porque el servidor es un solo proceso con una base local; si se levantaran dos servidores en la misma red, cada uno tendría datos distintos. La escala posible es vertical: usar una tablet más potente o mover el servidor a una computadora. Supabase puede absorber la parte de reportes, pero no las escrituras locales. Si la carga se triplicara, evaluaría mover el servidor a un equipo más capaz o rediseñar la arquitectura hacia un servidor central.

**3. ¿Las consultas pesadas están optimizadas para evitar que el servidor colapse al cargar miles de registros?**

Las optimicé en parte. Algunas consultas tienen límite, como los productos más vendidos o los últimos pedidos, y varias tablas tienen índices por claves foráneas y fechas. Pero la mayoría de los listados no tienen paginación: pedidos por rango, menú, usuarios y gastos devuelven todo lo que encuentran. Además, la lista de pedidos pendientes consulta los ítems uno por uno. Con miles de registros eso puede volverse lento, así que tengo pendiente agregar paginación, índices por fecha y estado, y consultas con JOIN.

---

## 5. Seguridad y Control de Accesos

**1. ¿Cómo se gestiona la autenticación de usuarios y la encriptación de contraseñas?**

Implementé el acceso con un PIN de cuatro dígitos que no se guarda en texto plano: se almacena con un hash PBKDF2 con sal distinta para cada usuario y comparación en tiempo constante. Además del PIN, el login exige que el dispositivo esté previamente emparejado y autorizado; el emparejamiento se hace con un código de un solo uso que vence a los cinco minutos. Las sesiones usan un token aleatorio y los permisos se separan por rol: mesero, cajero y administrador. Como mejoras pendientes tengo el límite de intentos de login y el uso de HTTPS, porque hoy el tráfico circula sin cifrar dentro de la red local.

**2. ¿Están protegidos los formularios y endpoints contra inyecciones SQL, XSS o CSRF?**

Contra inyección SQL uso las consultas parametrizadas de Room, sin armar el SQL concatenando lo que manda el usuario; por ejemplo, las búsquedas por id usan un parámetro y no un texto armado. XSS no aplica en la parte del servidor porque no devuelvo HTML ni uso un navegador embebido: la comunicación es JSON con una aplicación nativa. CSRF tampoco aplica porque no uso cookies de sesión, sino un token en el encabezado de autorización. La deuda pendiente más importante es cifrar la comunicación con TLS.

**3. ¿El sistema valida a nivel backend que el usuario logueado realmente tenga los permisos necesarios antes de modificar o eliminar un registro?**

En los endpoints principales sí valido la sesión y el rol antes de operar. Sin embargo, al revisar el código encontré que varias rutas de consulta y algunas de eliminación o edición no hacen esa validación, por ejemplo listar usuarios, listar pedidos, consultar el menú y borrar categorías o productos. En una red local de confianza el riesgo es menor, pero cualquier equipo conectado podría acceder sin iniciar sesión. Mi plan es centralizar la validación en el enrutador para que ninguna ruta quede sin control.

---

## 6. Frontend, Experiencia de Usuario y SEO

**1. Si el sistema incluye una interfaz pública, ¿necesita estrategias de SEO?**

No, porque el sistema no tiene una interfaz web pública ni un catálogo o portal de reservas. El servidor solo entrega datos a una aplicación Android nativa, así que no hay páginas HTML, metaetiquetas ni indexado que trabajar.

**2. ¿El diseño es completamente responsivo y ofrece una experiencia fluida en móviles y escritorio?**

La pantalla que pertenece a este repositorio es nativa de Android: muestra el estado del servidor, la IP, el código QR y la configuración de la impresora, y se adapta a la rotación del dispositivo. No es una interfaz web, por lo que el concepto de responsivo no aplica igual. La pantalla de venta es una aplicación cliente separada que no está en este repositorio; su experiencia de uso debe evaluarse en ese proyecto.

**3. Ante la caída del servidor o de la conexión, ¿cómo reacciona el frontend para comunicar el error sin quedarse en blanco?**

El servidor mantiene el WebSocket con un tiempo de espera para detectar conexiones caídas y el HTTP tiene su propio tiempo límite de lectura. La aplicación cliente es la que muestra el mensaje de error y vuelve a intentar la conexión; según el contrato que definimos, el login tiene un tiempo límite de cinco segundos y el WebSocket se reconecta solo. Como el cliente no está incluido en este repositorio, esa parte hay que verificarla en el proyecto de la aplicación de venta.

---

## Observaciones finales

Estas respuestas reflejan el estado actual del servidor. Los puntos que considero prioritarios antes de una presentación son: centralizar la validación de sesión y permisos en todas las rutas, agregar límite de intentos en el login, incorporar HTTPS, sumar pruebas automatizadas y mejorar el historial de Git. Son mejoras concretas y ya tengo identificado el orden en que las haría.
