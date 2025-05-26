package io.zabbix4j.api.service;

import io.zabbix4j.api.exception.ZabbixApiException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Service interface for Zabbix Item related API operations.
 * Provides methods for retrieving, creating, updating, and deleting items.
 *
 * @author CSJ
 */
public interface ItemService {

    /**
     * Retrieves items based on the provided parameters.
     * Corresponds to the "item.get" Zabbix API method.
     *
     * @param params A map of parameters for the "item.get" method (e.g., filter, hostids, output).
     * @return A JSONArray containing the retrieved item objects.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONArray get(Map<String, Object> params) throws ZabbixApiException;

    /**
     * Creates new items.
     * Corresponds to the "item.create" Zabbix API method.
     *
     * @param params A map of parameters defining the items to create (e.g., name, key_, hostid, type, value_type).
     * @return A JSONObject containing the IDs of the newly created items.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONObject create(Map<String, Object> params) throws ZabbixApiException;

    /**
     * Updates existing items.
     * Corresponds to the "item.update" Zabbix API method.
     *
     * @param params A map of parameters defining the updates for the items (e.g., itemid, status, delay).
     * @return A JSONObject containing the IDs of the updated items.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONObject update(Map<String, Object> params) throws ZabbixApiException;

    /**
     * Deletes items.
     * Corresponds to the "item.delete" Zabbix API method.
     * The Zabbix API expects a list of item IDs directly as parameters.
     *
     * @param itemIds A list of item IDs to delete.
     * @return A JSONObject containing the IDs of the deleted items.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONObject delete(List<String> itemIds) throws ZabbixApiException; // Changed to JSONObject
}
