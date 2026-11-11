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

package com.unicenta.pos.forms;

import com.unicenta.basic.BasicException;
import com.unicenta.data.loader.Session;
import com.unicenta.pos.util.AltEncrypter;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.swing.ImageIcon;
import javax.swing.JOptionPane;

/**
 *
 * @author adrianromero
 */
public class AppViewConnection {
    
    /** Creates a new instance of AppViewConnection */
    private AppViewConnection() {
    }
    
    /**
     *
     * @param props
     * @return
     * @throws BasicException
     */
    public static Session createSession(AppProperties props) throws BasicException {
        try {
            String dbURL=null;
            String sDBUser=null;
            String sDBPassword=null;
            String sUserPath = AppConfig.getDataDir().getAbsolutePath();   // OLPOS
            String filePath = sUserPath + "\\open.db";

            if (isJavaWebStart()) {
                Class.forName(props.getProperty("db.driver"), true, Thread.currentThread().getContextClassLoader());
            } else {
                URL[] urls = {new File(props.getProperty("db.driverlib")).toURI().toURL()};
                ClassLoader cloader = new URLClassLoader(urls);
                String driver = props.getProperty("db.driver");
                DriverWrapper driverWrapper = new DriverWrapper((Driver) Class.forName(driver, true, cloader).newInstance());
                DriverManager.registerDriver(driverWrapper);
            }
            if("true".equals(props.getProperty("db.multi"))) {
                if (!Files.exists(Paths.get(filePath))) {
                    ImageIcon icon = new ImageIcon("/com/unicenta/images/unicentaopos.png");
                    Object[] dbs = {
                    "0 - " + props.getProperty("db.name"),
                    "1 - " + props.getProperty("db1.name")};
        
                    Object s = (Object)JOptionPane.showInputDialog(
                        null, AppLocal.getIntString("message.databasechoose"),
                        "Selection", JOptionPane.OK_OPTION,
                        icon, dbs, props.getProperty("db.name"));
            
                    if (s.toString().startsWith("1")) {
                        sDBUser = props.getProperty("db1.user");
                        sDBPassword = props.getProperty("db1.password");
                        if (sDBUser != null && sDBPassword != null && sDBPassword.startsWith("crypt:")) {
                            AltEncrypter cypher = new AltEncrypter("cypherkey" + sDBUser);
                            sDBPassword = cypher.decrypt(sDBPassword.substring(6));
                        }
                        dbURL = props.getProperty("db1.URL") +
                        props.getProperty("db1.schema") +
                        props.getProperty("db1.options");
                    } else {
                        sDBUser = props.getProperty("db.user");
                        sDBPassword = props.getProperty("db.password");
                        if (sDBUser != null && sDBPassword != null && sDBPassword.startsWith("crypt:")) {
                            AltEncrypter cypher = new AltEncrypter("cypherkey" + sDBUser);
                            sDBPassword = cypher.decrypt(sDBPassword.substring(6));
                        }
                        dbURL = props.getProperty("db.URL") +
                        props.getProperty("db.schema") +
                        props.getProperty("db.options");                        
                    }
                } else {
                    sDBUser = props.getProperty("db.user");
                    sDBPassword = props.getProperty("db.password");
                    if (sDBUser != null && sDBPassword != null && sDBPassword.startsWith("crypt:")) {
                        AltEncrypter cypher = new AltEncrypter("cypherkey" + sDBUser);
                        sDBPassword = cypher.decrypt(sDBPassword.substring(6));
                    }
                    dbURL = props.getProperty("db.URL") +
                    props.getProperty("db.schema") +
                    props.getProperty("db.options");                    
                }    

            } else {
                sDBUser = props.getProperty("db.user");
                sDBPassword = props.getProperty("db.password");
                if (sDBUser != null && sDBPassword != null && sDBPassword.startsWith("crypt:")) {
                    AltEncrypter cypher = new AltEncrypter("cypherkey" + sDBUser);
                    sDBPassword = cypher.decrypt(sDBPassword.substring(6));
                }

                dbURL = props.getProperty("db.URL") +
                props.getProperty("db.schema") +
                props.getProperty("db.options");                
            }
            Session opened = new Session(dbURL, sDBUser,sDBPassword);
            lastConnection = new String[] {dbURL, sDBUser, sDBPassword};   // OLPOS : connexions supplementaires
            return opened;

        } catch (InstantiationException | IllegalAccessException | MalformedURLException | ClassNotFoundException e) {
            throw new BasicException(AppLocal.getIntString("message.databasedrivererror"), e);
        } catch (SQLException eSQL) {
            throw new BasicException(AppLocal.getIntString("message.databaseconnectionerror"), eSQL);
        }
    }

    /**
     * OLPOS : ouvre une connexion SUPPLEMENTAIRE a la base de la caisse, en
     * reutilisant le pilote JDBC deja enregistre par {@link #createSession}.
     *
     * <p>Pour les services qui tournent dans le meme programme que la caisse
     * (API mobile, envoi cuisine) : rappeler createSession() chargerait une
     * deuxieme copie du pilote dans un autre ClassLoader ; avec Derby embarque,
     * deux moteurs ouvriraient la meme base (risque de corruption). Avec
     * plusieurs bases (db.multi), la base choisie au demarrage est reprise.</p>
     */
    public static Session createAdditionalSession(AppProperties props) throws BasicException {
        try {
            String[] chosen = lastConnection;
            if (chosen != null) {
                return new Session(chosen[0], chosen[1], chosen[2]);
            }
            String sDBUser = props.getProperty("db.user");
            String sDBPassword = props.getProperty("db.password");
            if (sDBUser != null && sDBPassword != null && sDBPassword.startsWith("crypt:")) {
                AltEncrypter cypher = new AltEncrypter("cypherkey" + sDBUser);
                sDBPassword = cypher.decrypt(sDBPassword.substring(6));
            }
            return new Session(url(props, "db"), sDBUser, sDBPassword);
        } catch (SQLException eSQL) {
            throw new BasicException(AppLocal.getIntString("message.databaseconnectionerror"), eSQL);
        }
    }

    /** Adresse complete d'une base : URL + schema + options (format uniCenta 5.0). */
    static String url(AppProperties props, String prefix) {
        return nz(props.getProperty(prefix + ".URL")) + nz(props.getProperty(prefix + ".schema"))
                + nz(props.getProperty(prefix + ".options"));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Base ouverte par createSession() : {url, utilisateur, mot de passe}. */
    private static volatile String[] lastConnection;

    private static boolean isJavaWebStart() {

        try {
            Class.forName("javax.jnlp.ServiceManager");
            return true;
        } catch (ClassNotFoundException ue) {
            return false;
        }
    }
}