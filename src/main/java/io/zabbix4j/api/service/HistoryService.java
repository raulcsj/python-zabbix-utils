package io.zabbix4j.api.service;

import io.zabbix4j.api.exception.ZabbixApiException;
import org.json.JSONArray;

import java.util.Map;

/**
 * Service interface for Zabbix History related API operations.
 * Provides methods for retrieving historical data for items.
 *
 * @author CSJ
 */
public interface HistoryService {

    /**
     * Retrieves historical data for items based on the provided parameters.
     * Corresponds to the "history.get" Zabbix API method.
     *
     * @param params A map of parameters for the "history.get" method (e.g., history type, itemids, time_from, time_till, output).
     * @return A JSONArray containing the retrieved history objects.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONArray get(Map<String, Object> params) throws ZabbixApiException;
}
