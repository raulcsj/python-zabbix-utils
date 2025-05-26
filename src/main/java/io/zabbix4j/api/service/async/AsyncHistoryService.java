package io.zabbix4j.api.service.async;

import io.zabbix4j.api.exception.ZabbixApiException;
import org.json.JSONArray;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous service interface for Zabbix History related API operations.
 * All methods return a {@link CompletableFuture} to handle asynchronous responses.
 *
 * @author CSJ
 */
public interface AsyncHistoryService {

    /**
     * Asynchronously retrieves historical data for items based on the provided parameters.
     * Corresponds to the "history.get" Zabbix API method.
     *
     * @param params A map of parameters for the "history.get" method (e.g., history type, itemids, time_from, time_till, output).
     * @return A {@link CompletableFuture} which will complete with a JSONArray containing the retrieved history objects,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONArray> get(Map<String, Object> params);
}
