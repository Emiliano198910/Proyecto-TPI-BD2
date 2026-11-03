package tpi;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;

import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * TPI Bases de Datos II - Clínica veterinaria (MongoDB Atlas).
 * Colecciones: clientes, mascotas, turnos. Baja lógica con el campo "activo".
 * Los _id son numéricos autoincrementales (1, 2, 3...) usando la colección "contadores".
 */
public class App {

    static MongoDatabase db;
    static final Scanner sc = new Scanner(System.in);

    // Campos que UPDATE permite modificar (lista blanca) y su tipo.
    // Quedan fuera _id, activo, fechaAlta y fechaBaja: "activo" solo cambia con la baja logica.
    static final Map<String, Map<String, String>> EDITABLES = Map.of(
            "clientes", Map.of("nombre", "texto", "email", "texto", "telefono", "texto",
                    "direccion.calle", "texto", "direccion.ciudad", "texto"),
            "mascotas", Map.of("nombre", "texto", "especie", "texto", "raza", "texto",
                    "clienteId", "ref:clientes"),
            "turnos", Map.of("fecha", "fechahora", "motivo", "texto", "estado", "estado",
                    "mascotaId", "ref:mascotas"));
    static final List<String> ESTADOS = List.of("pendiente", "atendido", "cancelado");

    public static void main(String[] args) throws Exception {
        Map<String, String> env = leerEnv();
        try (MongoClient client = MongoClients.create(env.get("MONGO_URI"))) {
            db = client.getDatabase(env.get("DB_NAME"));
            db.runCommand(new Document("ping", 1)); // verifica la conexión
            System.out.println("Conectado a MongoDB Atlas");

            boolean salir = false;
            while (!salir) {
                System.out.println("\n=== CLINICA VETERINARIA ===");
                System.out.println("1. Clientes\n2. Mascotas\n3. Turnos\n0. Salir");
                switch (pedir("Opcion")) {
                    case "1" -> submenu("clientes");
                    case "2" -> submenu("mascotas");
                    case "3" -> submenu("turnos");
                    case "0" -> salir = true;
                    default -> System.out.println("Opcion invalida");
                }
            }
        }
    }

    // ---------- Menu ----------
    static void submenu(String col) {
        System.out.println("\n--- " + col.toUpperCase() + " ---");
        System.out.println("1. Crear\n2. Listar\n3. Actualizar\n4. Baja logica");
        if (col.equals("mascotas")) System.out.println("5. Listar con datos del cliente ($lookup)");
        try {
            switch (pedir("Opcion")) {
                case "1" -> crear(col);
                case "2" -> listar(col);
                case "3" -> actualizar(col);
                case "4" -> baja(col);
                case "5" -> { if (col.equals("mascotas")) mascotasConCliente(); }
                default -> System.out.println("Opcion invalida");
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Error: ID o dato invalido (" + e.getMessage() + ")");
        }
    }

    // ---------- ID autoincremental ----------
    // Un documento por colección en "contadores": { _id: "clientes", seq: 3 }
    // $inc es atómico, por lo que no se repiten IDs aunque haya altas simultáneas.
    static int siguienteId(String col) {
        Document r = db.getCollection("contadores").findOneAndUpdate(
                Filters.eq("_id", col),
                Updates.inc("seq", 1),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return r.getInteger("seq");
    }

    // ---------- CREATE ----------
    static void crear(String col) {
        // Primero se piden y validan los datos; el ID se asigna recién al final
        Document doc = new Document();
        switch (col) {
            case "clientes" -> {
                doc.append("nombre", pedir("Nombre"))
                   .append("email", pedir("Email"))
                   .append("telefono", pedir("Telefono"));
                // EMBEDDING: la direccion vive dentro del cliente
                doc.append("direccion", new Document("calle", pedir("Calle"))
                        .append("ciudad", pedir("Ciudad")));
            }
            case "mascotas" -> {
                // LINKING: la mascota referencia al cliente por su _id
                int clienteId = pedirInt("ID del cliente duenio");
                if (!existeActivo("clientes", clienteId)) {
                    System.out.println("El cliente no existe o esta dado de baja");
                    return;
                }
                doc.append("clienteId", clienteId)
                   .append("nombre", pedir("Nombre"))
                   .append("especie", pedir("Especie"))
                   .append("raza", pedir("Raza"));
                // EMBEDDING: las vacunas viven dentro de la mascota
                List<Document> vacunas = new ArrayList<>();
                String v = pedir("Vacuna (Enter para ninguna)");
                if (!v.isBlank()) {
                    vacunas.add(new Document("nombre", v).append("fecha", parseFecha(pedir("Fecha vacuna (AAAA-MM-DD)"))));
                }
                doc.append("vacunas", vacunas);
            }
            case "turnos" -> {
                // LINKING: el turno referencia a la mascota por su _id
                int mascotaId = pedirInt("ID de la mascota");
                if (!existeActivo("mascotas", mascotaId)) {
                    System.out.println("La mascota no existe o esta dada de baja");
                    return;
                }
                doc.append("mascotaId", mascotaId)
                   .append("fecha", parseFechaHora(pedir("Fecha y hora (AAAA-MM-DD HH:mm)")))
                   .append("motivo", pedir("Motivo"))
                   .append("estado", "pendiente");
            }
        }
        Document nuevo = new Document("_id", siguienteId(col));
        nuevo.putAll(doc);
        nuevo.append("activo", true).append("fechaAlta", new Date());
        db.getCollection(col).insertOne(nuevo);
        System.out.println("Creado con ID: " + nuevo.get("_id"));
    }

    // ---------- READ (solo activos) ----------
    static void listar(String col) {
        long n = 0;
        for (Document d : db.getCollection(col).find(Filters.eq("activo", true))) {
            System.out.println("ID " + d.get("_id") + " -> " + d.toJson());
            n++;
        }
        System.out.println("Total activos: " + n);
    }

    static void mascotasConCliente() {
        List<Document> res = db.getCollection("mascotas").aggregate(List.of(
                Aggregates.match(Filters.eq("activo", true)),
                Aggregates.lookup("clientes", "clienteId", "_id", "cliente")
        )).into(new ArrayList<>());
        res.forEach(d -> System.out.println("ID " + d.get("_id") + " -> " + d.toJson()));
    }

    // ---------- UPDATE ----------
    // Solo permite campos de la lista blanca y convierte el valor al tipo correcto.
    static void actualizar(String col) {
        int id = pedirInt("ID del documento");
        Map<String, String> campos = EDITABLES.get(col);
        System.out.println("Campos editables: " + String.join(", ", new TreeSet<>(campos.keySet())));
        String campo = pedir("Campo a modificar");
        String tipo = campos.get(campo);
        if (tipo == null) {
            System.out.println("Campo no editable (activo, fechaAlta, fechaBaja y _id estan protegidos)");
            return;
        }
        Object valo1r = convertir(tipo, pedir("Nuevo valor"));
        var r = db.getCollection(col).updateOne(
                Filters.and(Filters.eq("_id", id), Filters.eq("activo", true)),
                Updates.set(campo, valo1r));
        System.out.println(r.getMatchedCount() > 0
                ? "Actualizado"
                : "No se encontro un documento activo con ese ID");
    }

    // Convierte el texto ingresado al tipo que corresponde al campo
    static Object convertir(String tipo, String texto) {
        if (texto.isBlank()) throw new IllegalArgumentException("el valor no puede estar vacio");
        if (tipo.equals("texto")) return texto;
        if (tipo.equals("estado")) {
            String e = texto.toLowerCase();
            if (!ESTADOS.contains(e)) throw new IllegalArgumentException("el estado debe ser uno de " + ESTADOS);
            return e;
        }
        if (tipo.equals("fechahora")) return parseFechaHora(texto);
        if (tipo.startsWith("ref:")) {
            String colRef = tipo.substring(4);
            int refId = Integer.parseInt(texto.trim());
            if (!existeActivo(colRef, refId)) {
                throw new IllegalArgumentException("no existe un registro activo en " + colRef + " con ID " + refId);
            }
            return refId;
        }
        throw new IllegalArgumentException("tipo de campo desconocido");
    }

    // ---------- DELETE LOGICO ----------
    static void baja(String col) {
        int id = pedirInt("ID del documento");
        var r = db.getCollection(col).updateOne(
                Filters.and(Filters.eq("_id", id), Filters.eq("activo", true)),
                Updates.combine(Updates.set("activo", false), Updates.set("fechaBaja", new Date())));
        System.out.println(r.getModifiedCount() > 0
                ? "Baja logica realizada (el documento sigue en la base)"
                : "No se encontro un documento activo con ese ID");
    }

    // ---------- Utilidades ----------
    static Date parseFechaHora(String s) {
        try {
            return Date.from(LocalDateTime.parse(s.trim().replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("fecha invalida, use AAAA-MM-DD HH:mm");
        }
    }

    static Date parseFecha(String s) {
        try {
            return Date.from(LocalDate.parse(s.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("fecha invalida, use AAAA-MM-DD");
        }
    }

    static boolean existeActivo(String col, int id) {
        return db.getCollection(col)
                .countDocuments(Filters.and(Filters.eq("_id", id), Filters.eq("activo", true))) > 0;
    }

    static String pedir(String msg) {
        System.out.print(msg + ": ");
        return sc.nextLine().trim();
    }

    static int pedirInt(String msg) {
        return Integer.parseInt(pedir(msg)); // NumberFormatException es un IllegalArgumentException
    }

    static Map<String, String> leerEnv() throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String linea : Files.readAllLines(Paths.get(".env"))) {
            int i = linea.indexOf('=');
            if (i > 0) m.put(linea.substring(0, i).trim(), linea.substring(i + 1).trim().replace("\"", ""));
        }
        return m;
    }
}
