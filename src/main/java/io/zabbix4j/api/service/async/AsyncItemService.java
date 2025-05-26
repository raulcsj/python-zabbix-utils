package io.zabbix4j.api.service.async;

import io.zabbix4j.api.exception.ZabbixApiException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous service interface for Zabbix Item related API operations.
 * All methods return a {@link CompletableFuture} to handle asynchronous responses.
 *
 * @author CSJ
 */
public interface AsyncItemService {

    /**
     * Asynchronously retrieves items based on the provided parameters.
     * Corresponds to the "item.get" Zabbix API method.
     *
     * @param params A map of parameters for the "item.get" method (e.g., filter, hostids, output).
     * @return A {@link CompletableFuture} which will complete with a JSONArray containing the retrieved item objects,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONArray> get(Map<String, Object> params);

    /**
     * Asynchronously creates new items.
     * Corresponds to the "item.create" Zabbix API method.
     *
     * @param params A map of parameters defining the items to create (e.g., name, key_, hostid, type, value_type).
     * @return A {@link CompletableFuture} which will complete with a JSONObject containing the IDs of the newly created items,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONObject> create(Map<String, Object> params);

    /**
     * Asynchronously updates existing items.
     * Corresponds to the "item.update" Zabbix API method.
     *
     * @param params A map of parameters defining the updates for the items (e.g., itemid, status, delay).
     * @return A {@link CompletableFuture} which will complete with a JSONObject containing the IDs of the updated items,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONObject> update(Map<String, Object> params);

    /**
     * Asynchronously deletes items.
     * Corresponds to the "item.delete" Zabbix API method.
     * The Zabbix API expects a list of item IDs directly as parameters.
     *
     * @param itemIds A list of item IDs to delete.
     * @return A {@link CompletableFuture} which will complete with a JSONObject containing the IDs of the deleted items,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONObject> delete(List<String> itemIds);
}
