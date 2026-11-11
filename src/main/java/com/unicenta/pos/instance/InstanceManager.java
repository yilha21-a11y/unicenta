//    uniCenta oPOS  - Touch Friendly Point Of Sale
//    Copyright (c) 2009-2018 uniCenta & previous Openbravo POS works
//    https://unicenta.com
//
//    This file is part of uniCenta oPOS
//
//    uniCenta oPOS is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//   uniCenta oPOS is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with uniCenta oPOS.  If not, see <http://www.gnu.org/licenses/>.

package com.unicenta.pos.instance;

import java.rmi.AlreadyBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 *
 * @author adrianromero
 */
public class InstanceManager {
    
    /** Port RMI local d'OLPOS 5 (uniCenta : 1099). */
    public static final int PORT = Integer.getInteger("olpos.instance.port", 10995);
    /** Nom de l'objet enregistre (uniCenta : "AppMessage"). */
    public static final String NAME = "OLPOS5.AppMessage";

    /** Sockets du registre ouvertes sur 127.0.0.1 seulement. */
    static final class LoopbackServerSocketFactory implements java.rmi.server.RMIServerSocketFactory {
        @Override
        public java.net.ServerSocket createServerSocket(int port) throws java.io.IOException {
            return new java.net.ServerSocket(port, 50, java.net.InetAddress.getLoopbackAddress());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LoopbackServerSocketFactory;
        }

        @Override
        public int hashCode() {
            return LoopbackServerSocketFactory.class.hashCode();
        }
    }

    private final Registry m_registry;
    private final AppMessage m_message;
    
    /** Creates a new instance of InstanceManager
     * @param message
     * @throws java.rmi.RemoteException
     * @throws java.rmi.AlreadyBoundException */
    public InstanceManager(AppMessage message) throws RemoteException, AlreadyBoundException {

        // OLPOS : port et nom propres (une caisse uniCenta ouverte sur le meme PC
        // utilise 1099/"AppMessage" : elle ne doit jamais etre prise pour OLPOS),
        // registre accessible depuis ce PC uniquement (127.0.0.1).
        m_registry = LocateRegistry.createRegistry(PORT, null, new LoopbackServerSocketFactory());

        m_message = message;

        AppMessage stub = (AppMessage) UnicastRemoteObject.exportObject(m_message, 0, null,
                new LoopbackServerSocketFactory());
        m_registry.bind(NAME, stub);

        // jLabel1.setText("Server ready");
    }    
}
