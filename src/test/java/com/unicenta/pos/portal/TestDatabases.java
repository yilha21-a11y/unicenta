package com.unicenta.pos.portal;

import com.unicenta.pos.testenv.TestEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Choix de la base des tests : Derby (par defaut) ou MySQL de TEST isole.
 * <pre>
 * mvn test -Dolpos.test.db=mysql -Dolpos.test.mysql.file=C:\...\acces.txt
 * </pre>
 * Le fichier (local, jamais dans le depot) contient hote, port, base,
 * utilisateur, mot_de_passe. Les garde-fous de TestEnvironment refusent toute
 * base qui ne s'appelle pas olpos_test..., hors 127.0.0.1 ou sur le port 3306.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    public static String kind() {
        return "mysql".equalsIgnoreCase(System.getProperty("olpos.test.db")) ? "mysql" : "derby";
    }

    public static TestEnvironment.MySql mysql() throws Exception {
        if (!"mysql".equals(kind())) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(System.getProperty("olpos.test.mysql.file"))) {
            p.load(in);
        }
        return new TestEnvironment.MySql(p.getProperty("hote"), Integer.parseInt(p.getProperty("port").trim()),
                p.getProperty("base").trim(), p.getProperty("utilisateur").trim(), p.getProperty("mot_de_passe").trim());
    }

    public static TestEnvironment.Result create(File dir, File root) throws Exception {
        return TestEnvironment.create(dir, true, 0, null, root, mysql());
    }
}
