//    OLPOS (derive d'uniCenta oPOS) - services de la caisse
//    Licence GPL v3 (voir LICENSE.md).
package com.unicenta.pos.api;

import com.unicenta.data.loader.Session;
import com.unicenta.pos.forms.AppProperties;
import com.unicenta.pos.printer.DeviceTicket;
import com.unicenta.pos.printer.TicketParser;
import com.unicenta.pos.sales.restaurant.TableLocks;
import java.io.IOException;

/**
 * Services OLPOS demarres avec la caisse :
 * <ul>
 *   <li>envoi en cuisine ({@link KitchenService}), utilise par le bouton de la
 *       caisse ET par les telephones ;</li>
 *   <li>acces telephone ({@link ApiServer}), si {@code api.enabled=true}.</li>
 * </ul>
 * Une seule connexion dediee a la base (fournie par la caisse) et une seule
 * file d'impression pour tous les bons.
 */
public final class OlposServices {

    /** Au-dela, une impression sans reponse est "a verifier". */
    static final long PRINT_TIMEOUT_MILLIS = 20000;

    private final ApiServer apiServer;
    private final PrintRunner runner;

    private OlposServices(ApiServer apiServer, PrintRunner runner) {
        this.apiServer = apiServer;
        this.runner = runner;
    }

    public static OlposServices start(AppProperties props, Session session, DeviceTicket devices, TicketParser parser)
            throws IOException {
        MobileDataService data = new MobileDataService(session);
        MobilePrinter printer = new TillPrinter(devices, parser, props);
        ApiConfig config = new ApiConfig(props);
        if (config.isEnabled()) {
            ApiServer server = new ApiServer(config, data, new ApiSessions(config), TableLocks.get(), printer,
                    PRINT_TIMEOUT_MILLIS);   // installe aussi le service cuisine de la caisse
            server.start();
            return new OlposServices(server, null);
        }
        // Acces telephone desactive : le bouton Cuisine de la caisse garde le meme service.
        PrintRunner runner = new PrintRunner(PRINT_TIMEOUT_MILLIS);
        MobileOrderService orders = new MobileOrderService(data, TableLocks.get(), 2000);
        KitchenService.install(new KitchenService(data, orders, TableLocks.get(), printer, runner));
        return new OlposServices(null, runner);
    }

    /** Serveur de l'acces telephone, ou null s'il est desactive. */
    public ApiServer getApiServer() {
        return apiServer;
    }

    public void stop() {
        KitchenService.install(null);
        if (apiServer != null) {
            apiServer.stop();
        }
        if (runner != null) {
            runner.shutdown();
        }
    }
}
