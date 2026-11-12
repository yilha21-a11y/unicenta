//    OLPOS Portail Patron - point d'extension pour un futur module d'analyse (IA)
//    Licence GPL v3 (voir ../LICENSE.md).
package com.olpos.portal.insights;

import java.util.List;
import java.util.Map;

/**
 * FUTUR module d'analyse (non implemente). Il recevra UNIQUEMENT le resume
 * statistique d'UN restaurant deja calcule par StatsService (jamais la base,
 * jamais un autre restaurant, jamais de donnee personnelle de client) et
 * renverra des observations en francais, affichees en lecture seule.
 *
 * Regles prevues : activation explicite par restaurant, fournisseur et cout
 * documentes avant tout envoi de donnees, journal des demandes, aucune action
 * sur la caisse.
 */
public interface InsightsProvider {

    /** Observations pour le resume d'une periode (ex. "Les ventes du mardi soir baissent de 12 %"). */
    List<String> insights(Map<String, Object> periodSummary);
}
