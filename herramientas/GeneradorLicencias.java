// Generador de licencias de NexCrono Pistola — CodevNexus
// Uso (Java 11 o superior; sirve el de Android Studio):
//   java GeneradorLicencias.java llaves
//   java GeneradorLicencias.java emitir --telefono A1B2-C3D4-E5F6-0718 --cliente "FastTime Ecuador" --vence 2027-12-31 [--plan Pro] [--salida archivo.lic]
//   java GeneradorLicencias.java ver archivo.lic
// --vence acepta una fecha (aaaa-mm-dd), "perpetua" o "+N" (N días desde hoy).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.LocalDate;
import java.util.*;

public class GeneradorLicencias {

    static final String ARCHIVO_CLAVE = "codevnexus-licencias.key";

    public static void main(String[] a) throws Exception {
        if (a.length == 0) { ayuda(); return; }
        switch (a[0]) {
            case "llaves": llaves(); break;
            case "emitir": emitir(opciones(a)); break;
            case "ver": ver(a.length > 1 ? a[1] : null); break;
            default: ayuda();
        }
    }

    static void ayuda() {
        System.out.println("java GeneradorLicencias.java llaves");
        System.out.println("java GeneradorLicencias.java emitir --telefono CODIGO --cliente \"Nombre\" --vence 2027-12-31|perpetua|+365 [--plan Pro] [--salida x.lic] [--clave " + ARCHIVO_CLAVE + "]");
        System.out.println("java GeneradorLicencias.java ver archivo.lic");
    }

    static Map<String, String> opciones(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i + 1 < a.length; i += 2) {
            if (!a[i].startsWith("--")) throw new IllegalArgumentException("Opción inválida: " + a[i]);
            m.put(a[i].substring(2), a[i + 1]);
        }
        return m;
    }

    static void llaves() throws Exception {
        Path p = Paths.get(ARCHIVO_CLAVE);
        if (Files.exists(p)) {
            System.out.println("Ya existe " + ARCHIVO_CLAVE + ". No se sobrescribe: si la pierdes o la cambias, las licencias emitidas dejan de servir.");
            System.out.println("Clave pública (para la app): " + publicaDesde(p));
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        Files.write(p, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII));
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        Files.write(Paths.get("clave-publica.txt"), pub.getBytes(StandardCharsets.US_ASCII));
        System.out.println("Clave PRIVADA guardada en " + ARCHIVO_CLAVE + "  ← guárdala en lugar seguro, NUNCA la subas al repositorio.");
        System.out.println("Clave PÚBLICA (va en la app, secreto NEXCRONO_LICENSE_KEY de GitHub):");
        System.out.println(pub);
    }

    static PrivateKey privada(String ruta) throws Exception {
        byte[] b = Base64.getDecoder().decode(new String(Files.readAllBytes(Paths.get(ruta)), StandardCharsets.US_ASCII).trim());
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(b));
    }

    static String publicaDesde(Path ruta) throws Exception {
        Path pub = Paths.get("clave-publica.txt");
        return Files.exists(pub) ? new String(Files.readAllBytes(pub), StandardCharsets.US_ASCII).trim() : "(ver clave-publica.txt)";
    }

    static void emitir(Map<String, String> o) throws Exception {
        String tel = req(o, "telefono").toUpperCase().trim();
        if (!tel.matches("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}")) throw new IllegalArgumentException("El código del teléfono debe tener la forma XXXX-XXXX-XXXX-XXXX (está en Ajustes → Licencia)");
        String cliente = req(o, "cliente").replace("\n", " ").trim();
        String vence = req(o, "vence").trim();
        if (vence.startsWith("+")) vence = LocalDate.now().plusDays(Long.parseLong(vence.substring(1))).toString();
        else if (!vence.equalsIgnoreCase("perpetua")) vence = LocalDate.parse(vence).toString();
        String id = UUID.randomUUID().toString();
        String datos = "app=pistola\n" +
                "cliente=" + cliente + "\n" +
                "telefono=" + tel + "\n" +
                "plan=" + o.getOrDefault("plan", "Estandar").replace("\n", " ") + "\n" +
                "emitida=" + LocalDate.now() + "\n" +
                "vence=" + vence.toLowerCase().replace("perpetua", "perpetua") + "\n" +
                "id=" + id + "\n";
        byte[] bytes = datos.getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(privada(o.getOrDefault("clave", ARCHIVO_CLAVE)));
        s.update(bytes);
        String lic = "NXCLIC1\n" + Base64.getEncoder().encodeToString(bytes) + "\n" + Base64.getEncoder().encodeToString(s.sign()) + "\n";
        String salida = o.getOrDefault("salida", "licencia-" + cliente.replaceAll("[^A-Za-z0-9]+", "_") + "-" + tel + ".lic");
        Files.write(Paths.get(salida), lic.getBytes(StandardCharsets.US_ASCII));
        System.out.println("Licencia creada: " + salida);
        System.out.print(datos);
    }

    static void ver(String ruta) throws Exception {
        if (ruta == null) { ayuda(); return; }
        List<String> l = new ArrayList<>();
        for (String x : Files.readAllLines(Paths.get(ruta))) if (!x.trim().isEmpty()) l.add(x.trim());
        System.out.print(new String(Base64.getDecoder().decode(l.get(1)), StandardCharsets.UTF_8));
    }

    static String req(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Falta --" + k);
        return v;
    }
}
