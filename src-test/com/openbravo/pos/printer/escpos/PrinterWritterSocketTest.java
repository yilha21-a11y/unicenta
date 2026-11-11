package com.openbravo.pos.printer.escpos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/** Imprimante reseau (TCP 9100) simulee par un ServerSocket local. */
public class PrinterWritterSocketTest {

    /** Fausse imprimante : accepte des connexions et garde tout ce qu'elle recoit. */
    public static final class FakeNetworkPrinter implements AutoCloseable {
        public final ServerSocket server;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CountDownLatch firstJob = new CountDownLatch(1);

        public FakeNetworkPrinter() throws IOException {
            server = new ServerSocket(0);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!server.isClosed()) {
                        try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                            byte[] buf = new byte[4096];
                            int n;
                            int total = 0;
                            while ((n = in.read(buf)) > 0) {
                                synchronized (received) {
                                    received.write(buf, 0, n);
                                }
                                total += n;
                            }
                            // une connexion vide (test "l'imprimante repond-elle ?") n'est pas un bon
                            if (total > 0) {
                                firstJob.countDown();
                            }
                        } catch (IOException e) {
                            // fermeture du serveur
                        }
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        public String address() {
            return "127.0.0.1:" + server.getLocalPort();
        }

        /** Attend la fin d'un premier bon puis renvoie le texte recu (ISO-8859-1 : octets ESC/POS bruts). */
        public String awaitText() throws InterruptedException {
            assertTrue("aucun bon recu", firstJob.await(10, TimeUnit.SECONDS));
            return text();
        }

        /**
         * Attend que le texte recu contienne {@code marker} : un bon complet peut
         * etre precede de la connexion d'initialisation de l'imprimante (ESC @).
         */
        public String awaitContains(String marker) throws InterruptedException {
            long end = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < end) {
                String t = text();
                if (t.contains(marker)) {
                    return t;
                }
                Thread.sleep(50);
            }
            return text();
        }

        private String text() {
            synchronized (received) {
                return new String(received.toByteArray(), StandardCharsets.ISO_8859_1);
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    @Test
    public void envoieLesOctetsALImprimanteReseau() throws Exception {
        try (FakeNetworkPrinter printer = new FakeNetworkPrinter()) {
            PrinterWritterSocket w = new PrinterWritterSocket(printer.address());
            w.write("Bonjour ");
            w.write("cuisine".getBytes(StandardCharsets.US_ASCII));
            w.flush();
            assertEquals("Bonjour cuisine", printer.awaitText());
            w.close();
        }
    }

    @Test
    public void adresses() {
        assertEquals("9100", PrinterWritterSocket.parse("192.168.1.50")[1]);
        assertEquals("9101", PrinterWritterSocket.parse("imprimante-cuisine:9101")[1]);
        assertEquals("imprimante-cuisine", PrinterWritterSocket.parse(" imprimante-cuisine:9101 ")[0]);
        for (String bad : new String[] {"", "hote:0", "hote:70000", "hote:abc"}) {
            try {
                PrinterWritterSocket.parse(bad);
                fail("adresse acceptee : " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void imprimanteInjoignableDetecteeAvantImpression() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }   // port libere : plus personne n'ecoute
        try {
            PrinterWritterSocket.checkReachable("127.0.0.1:" + port, 1000);
            fail("imprimante injoignable consideree comme joignable");
        } catch (IOException expected) {
            // ok
        }
    }
}
