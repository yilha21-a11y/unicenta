package com.unicenta.pos.api;

import com.unicenta.data.loader.Session;
import com.unicenta.pos.forms.AppConfig;
import com.unicenta.pos.forms.AppViewConnection;
import com.unicenta.pos.testenv.TestEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.AfterClass;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * HTTPS de l'acces telephone : certificat du PC signe par une autorite locale
 * (comme OLPOS-HTTPS.cmd), verification NORMALE du certificat cote client.
 */
public class ApiHttpsTest {

    private static Session tillSession;
    private static Session apiSession;
    private static ApiServer server;
    private static File dir;
    private static SSLContext trustingLocalCa;

    private static void keytool(File cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(System.getProperty("java.home"), "bin/keytool").getPath());
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
        }
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool " + args[0] + " : " + out);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeClass
    public static void demarrer() throws Exception {
        File root = new File(".").getCanonicalFile();
        TestEnvironment.Result env = TestEnvironment.create(new File(root, "build/test-work/api-https"), true, 0,
                "https-" + System.nanoTime(), root);
        dir = new File(root, "build/test-work/api-https/https");
        dir.mkdirs();
        for (File f : dir.listFiles()) {
            f.delete();
        }
        String pw = "test-" + System.nanoTime();
        keytool(dir, "-genkeypair", "-alias", "olpos-ca", "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "3650", "-dname", "CN=OLPOS test CA", "-ext", "bc:c=ca:true,pathlen:0",
                "-ext", "ku:c=keyCertSign,cRLSign", "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", pw);
        keytool(dir, "-exportcert", "-alias", "olpos-ca", "-keystore", "ca.p12", "-storepass", pw, "-rfc",
                "-file", "olpos-ca.cer");
        keytool(dir, "-genkeypair", "-alias", "olpos", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "825",
                "-dname", "CN=caisse", "-keystore", "olpos-https.p12", "-storetype", "PKCS12", "-storepass", pw);
        keytool(dir, "-certreq", "-alias", "olpos", "-keystore", "olpos-https.p12", "-storepass", pw, "-file", "s.csr");
        keytool(dir, "-gencert", "-alias", "olpos-ca", "-keystore", "ca.p12", "-storepass", pw, "-infile", "s.csr",
                "-outfile", "s.cer", "-validity", "825", "-rfc", "-ext", "san=ip:127.0.0.1,dns:localhost",
                "-ext", "eku=serverAuth");
        keytool(dir, "-importcert", "-alias", "olpos-ca", "-keystore", "olpos-https.p12", "-storepass", pw,
                "-file", "olpos-ca.cer", "-noprompt");
        keytool(dir, "-importcert", "-alias", "olpos", "-keystore", "olpos-https.p12", "-storepass", pw,
                "-file", "s.cer", "-noprompt");
        Files.write(new File(dir, "password.txt").toPath(), (pw + "\r\n").getBytes(StandardCharsets.UTF_8));

        AppConfig config = new AppConfig(env.configFile);
        config.load();
        config.setProperty("api.bind", "127.0.0.1");
        config.setProperty("api.port", "0");
        config.setProperty("api.https.enabled", "true");
        config.setProperty("api.https.keystore", new File(dir, "olpos-https.p12").getPath());
        config.setProperty("api.https.caport", Integer.toString(freePort()));
        tillSession = AppViewConnection.createSession(config);
        apiSession = AppViewConnection.createAdditionalSession(config);
        server = new ApiServer(new ApiConfig(config), new MobileDataService(apiSession));
        server.start();

        // Le "telephone" : fait confiance UNIQUEMENT a l'autorite locale installee
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (InputStream in = new FileInputStream(new File(dir, "olpos-ca.cer"))) {
            trust.setCertificateEntry("olpos-ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        trustingLocalCa = SSLContext.getInstance("TLS");
        trustingLocalCa.init(null, tmf.getTrustManagers(), null);
    }

    @AfterClass
    public static void arreter() {
        if (server != null) {
            server.stop();
        }
        if (apiSession != null) {
            apiSession.close();
        }
        if (tillSession != null) {
            tillSession.close();
        }
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    public void connexionHttpsVerifieeAvecLAutoriteLocale() throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://127.0.0.1:" + server.getPort()
                + "/api/v1/health").openConnection();
        c.setSSLSocketFactory(trustingLocalCa.getSocketFactory());   // verification du nom (SAN) conservee
        assertEquals(200, c.getResponseCode());
        String body = new String(read(c.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(body, body.contains("\"status\":\"ok\""));
        assertTrue(c.getCipherSuite() != null);
    }

    @Test
    public void certificatRefuseSansLAutoriteLocale() throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://127.0.0.1:" + server.getPort()
                + "/api/v1/health").openConnection();
        try {
            c.getResponseCode();   // magasin de confiance par defaut : l'autorite locale n'y est pas
            fail("le certificat aurait du etre refuse");
        } catch (SSLHandshakeException expected) {
            // la verification des certificats n'est pas desactivee
        }
    }

    @Test
    public void nomVerifie() throws Exception {
        // "localhost" est dans le certificat, mais pas "127.0.0.2" : refuse
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://127.0.0.2:" + server.getPort()
                + "/api/v1/health").openConnection();
        c.setSSLSocketFactory(trustingLocalCa.getSocketFactory());
        try {
            c.getResponseCode();
            fail("nom non verifie");
        } catch (java.io.IOException expected) {
            // SSLPeerUnverifiedException / connexion refusee : jamais accepte
        }
    }

    @Test
    public void pageDuCertificatSansSecret() throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL("http://127.0.0.1:" + server.getCaPort()
                + "/olpos-ca.cer").openConnection();
        assertEquals(200, c.getResponseCode());
        assertArrayEquals(Files.readAllBytes(new File(dir, "olpos-ca.cer").toPath()), read(c.getInputStream()));
        java.net.HttpURLConnection api = (java.net.HttpURLConnection) new URL("http://127.0.0.1:" + server.getCaPort()
                + "/api/v1/health").openConnection();
        String page = new String(read(api.getInputStream()), StandardCharsets.UTF_8);
        assertTrue("pas d'API sur la page du certificat", !page.contains("\"status\""));
        assertTrue(page.contains("SHA-256"));
    }
}
