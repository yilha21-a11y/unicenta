//    uniCenta oPOS - imprimante de tickets reseau (ESC/POS en TCP, port 9100 en general)
//    Licence GPL v3 (voir LICENSE.md).
package com.openbravo.pos.printer.escpos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Envoie les donnees ESC/POS a une imprimante reseau ("raw TCP", port 9100 sur
 * la plupart des imprimantes de tickets Epson, Star, Bixolon...).
 *
 * <p>Configuration : {@code machine.printer.N=epson:socket,192.168.1.50:9100}
 * (port 9100 par defaut si absent). Une connexion par bon : ouverte au premier
 * envoi, fermee a la fin du bon ({@link #internalFlush()}).</p>
 *
 * <p>Comme les autres connexions (serie, fichier), l'ecriture se fait en
 * arriere-plan : une erreur est journalisee mais ne remonte pas a l'appelant.
 * L'impression n'est donc jamais "confirmee" ; l'API mobile verifie que
 * l'imprimante repond AVANT d'imprimer ({@link #checkReachable}).</p>
 */
public class PrinterWritterSocket extends PrinterWritter {

    public static final int DEFAULT_PORT = 9100;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int WRITE_TIMEOUT_MS = 10000;
    private static final Logger LOG = Logger.getLogger(PrinterWritterSocket.class.getName());

    private final String host;
    private final int port;
    private Socket socket;
    private OutputStream out;
    private boolean failed;     // connexion impossible pour ce bon : on ignore la suite jusqu'au flush

    /** @param address "hote:port" ou "hote" (port 9100) */
    public PrinterWritterSocket(String address) {
        String[] hp = parse(address);
        this.host = hp[0];
        this.port = Integer.parseInt(hp[1]);
    }

    /** Decoupe "hote:port" ; leve IllegalArgumentException si l'adresse est invalide. */
    public static String[] parse(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("Adresse d'imprimante reseau vide (attendu : hote:9100)");
        }
        String a = address.trim();
        String h = a;
        String p = Integer.toString(DEFAULT_PORT);
        int colon = a.lastIndexOf(':');
        if (colon > 0 && a.indexOf(':') == colon) {   // pas d'IPv6 sans crochets
            h = a.substring(0, colon);
            p = a.substring(colon + 1);
        }
        try {
            int n = Integer.parseInt(p);
            if (n < 1 || n > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port d'imprimante invalide : " + p);
        }
        return new String[] {h, p};
    }

    /**
     * Verifie que l'imprimante accepte une connexion (sans rien imprimer).
     * @throws IOException si elle ne repond pas
     */
    public static void checkReachable(String address, int timeoutMs) throws IOException {
        String[] hp = parse(address);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), timeoutMs);
        }
    }

    public String getAddress() {
        return host + ":" + port;
    }

    @Override
    protected void internalWrite(byte[] data) {
        if (failed) {
            return;
        }
        try {
            if (socket == null) {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(WRITE_TIMEOUT_MS);
                out = socket.getOutputStream();
            }
            out.write(data);
        } catch (IOException e) {
            failed = true;
            LOG.log(Level.WARNING, "Imprimante reseau " + getAddress() + " : envoi impossible", e);
            closeQuietly();
        }
    }

    @Override
    protected void internalFlush() {
        try {
            if (out != null && !failed) {
                out.flush();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Imprimante reseau " + getAddress() + " : fin d'envoi impossible", e);
        } finally {
            closeQuietly();
            failed = false;   // le bon suivant retentera une connexion
        }
    }

    @Override
    protected void internalClose() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // rien a faire
            }
        }
        socket = null;
        out = null;
    }
}
