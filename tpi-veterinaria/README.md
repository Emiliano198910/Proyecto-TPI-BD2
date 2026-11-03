# TPI Bases de Datos II – Clínica Veterinaria

Aplicación de consola en **Java** que gestiona clientes, mascotas y turnos de una clínica veterinaria sobre una base **NoSQL en la nube (MongoDB Atlas)**. Incluye CRUD con **baja lógica** y un script de **backup** con `mongodump`.

**Integrantes:** Emiliano Fiscina, Lucas Matias Catanzaro
**Materia:** Bases de Datos II – Tecnicatura Universitaria en Programación (UTN)

---

## Tecnologías

| Componente | Detalle |
|---|---|
| Lenguaje | Java 17 |
| Gestor de dependencias | Maven |
| Base de datos | MongoDB Atlas (cluster gratuito M0) |
| Driver | `mongodb-driver-sync` 5.2.0 (driver nativo) |
| Backup | `mongodump` (MongoDB Database Tools) |

## Requisitos

1. **JDK 17 o superior**
2. **Maven** (o un IDE que lo incluya, como IntelliJ)
3. **MongoDB Database Tools** instalado, para usar `mongodump` y `mongorestore`
4. Conexión a internet y un cluster de Atlas activo

## Estructura del proyecto

```
tpi-veterinaria/
├── src/main/java/tpi/App.java   # aplicación: conexión y CRUD
├── pom.xml                      # dependencias de Maven
├── .env.example                 # modelo del archivo de configuración
├── backup.bat                   # backup en Windows
├── backup.sh                    # backup en Linux/Mac
└── README.md
```

## Configuración

Archivo '.env' :

```
MONGO_URI=mongodb+srv://USUARIO:PASSWORD@cluster0.xxxxx.mongodb.net/?appName=Cluster0
DB_NAME=tpi_veterinaria
```


**En Atlas debe estar configurado:**
- *Database Users*: un usuario de base de datos con permisos de lectura y escritura.
- *Network Access*: la IP `0.0.0.0/0` habilitada.
- El cluster activo (los clusters gratuitos se pausan por inactividad).

## Ejecución

Desde la carpeta raíz del proyecto (donde está el `.env`):

```
mvn compile exec:java
```

También se puede ejecutar `App.main()` desde IntelliJ. Al iniciar debe mostrar **"Conectado a MongoDB Atlas"** y el menú principal.

## Funcionalidades

Menú por colección (`clientes`, `mascotas`, `turnos`):

| Opción | Operación | Descripción |
|---|---|---|
| 1 | **Crear** | Inserta un documento con ID autoincremental, `activo: true` y fecha de alta |
| 2 | **Listar** | Muestra solo los registros activos |
| 3 | **Actualizar** | Modifica un campo editable, validando su tipo |
| 4 | **Baja lógica** | Cambia `activo` a `false` y guarda `fechaBaja`; no borra el documento |
| 5 | **Listar con cliente** | Solo en mascotas: une cada mascota con su cliente mediante `$lookup` |

**Campos editables en Actualizar** (los demás están protegidos):

| Colección | Campos |
|---|---|
| `clientes` | `nombre`, `email`, `telefono`, `direccion.calle`, `direccion.ciudad` |
| `mascotas` | `nombre`, `especie`, `raza`, `clienteId` |
| `turnos` | `fecha`, `motivo`, `estado`, `mascotaId` |

`_id`, `activo`, `fechaAlta` y `fechaBaja` no se pueden modificar manualmente.

## Modelo de datos

Base `tpi_veterinaria`, con tres colecciones relacionadas y una auxiliar:

| Colección | Contenido | Relación |
|---|---|---|
| `clientes` | Dueños; la **dirección está incrustada** (embedding) | – |
| `mascotas` | Animales; las **vacunas están incrustadas** (embedding) | `clienteId` → `clientes` (linking) |
| `turnos` | Consultas agendadas | `mascotaId` → `mascotas` (linking) |
| `contadores` | Auxiliar: último ID usado de cada colección | – |

**Criterio:** se incrusta lo pequeño, acotado y que se lee junto con el documento padre (dirección, vacunas); se referencia lo que crece sin límite o se comparte (turnos, mascotas).

**Baja lógica:** ningún documento se elimina. Todas las lecturas filtran `activo: true`, y antes de crear una mascota o un turno se valida que el cliente o la mascota referenciados existan y estén activos.

**IDs autoincrementales:** el programa incrementa un contador en la colección `contadores` con `$inc`, que es una operación atómica.

**Fechas:** la fecha del turno, la de las vacunas, `fechaAlta` y `fechaBaja` se guardan como tipo fecha (`Date`). Se ingresan como `AAAA-MM-DD HH:mm` (turnos) y `AAAA-MM-DD` (vacunas).

### Ejemplo de documento (`mascotas`)

```json
{
  "_id": 1,
  "clienteId": 1,
  "nombre": "Firulais",
  "especie": "Perro",
  "raza": "Mestizo",
  "vacunas": [ { "nombre": "Antirrábica", "fecha": "2026-03-10" } ],
  "activo": true,
  "fechaAlta": "2026-10-07T16:22:00Z"
}
```

## Backup y restauración

**Backup** (requiere `mongodump` instalado y en el PATH, o junto al script):

```
backup.bat          # Windows
./backup.sh         # Linux / Mac  (primero: chmod +x backup.sh)
```

El script usa **rutas relativas**, lee el `.env`, crea `resguardos_tpi/<fecha-hora>/` y ejecuta:

```
mongodump --uri="<MONGO_URI>" --db="<DB_NAME>" --out="resguardos_tpi/<fecha-hora>"
```

Si `mongodump` falla, el script muestra un error, elimina la carpeta vacía y no informa éxito.

**Restauración** (probar en una base distinta, para no pisar la original):

```
mongorestore --uri="<MONGO_URI>" --nsFrom="tpi_veterinaria.*" --nsTo="tpi_test.*" resguardos_tpi/<fecha-hora>
```

**RPO / RTO:** con backup manual diario el RPO es de hasta 24 horas; la restauración de la base completa tardó aproximadamente 1,66 segundos en la prueba realizada.

## Solución de problemas

| Problema | Causa probable |
|---|---|
| `No such file: .env` | Se ejecutó desde otra carpeta; hacerlo desde la raíz del proyecto |
| `bad auth : authentication failed` | Usuario o contraseña del `.env` incorrectos |
| Timeout / `SSLException internal_error` | IP no permitida en *Network Access* o cluster en pausa |
| `mongodump no se reconoce` | Instalar MongoDB Database Tools o copiar `mongodump.exe` junto al script |
| `Error: ID o dato invalido` | ID inexistente, no numérico o fecha con formato incorrecto |

## Limitaciones conocidas

- La baja es lógica y **no hay baja en cascada**: dar de baja un cliente no da de baja sus mascotas.
- No hay opción para reactivar un registro dado de baja.
- No se definieron índices adicionales ni validación de esquema (`$jsonSchema`) en las colecciones.
- Si un alta falla luego de consumir un ID, puede quedar un salto en la numeración.
