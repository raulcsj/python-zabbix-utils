package io.zabbix4j.api.service;

import io.zabbix4j.api.exception.ZabbixApiException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Service interface for Zabbix Host related API operations.
 * Provides methods for retrieving, creating, updating, and deleting hosts.
 *
 * @author CSJ
 */
public interface HostService {

    /**
     * Retrieves hosts based on the provided parameters.
     * Corresponds to the "host.get" Zabbix API method.
     *
     * @param params A map of parameters for the "host.get" method (e.g., filter, output, selectInterfaces).
     * @return A JSONArray containing the retrieved host objects.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONArray get(Map<String, Object> params) throws ZabbixApiException;

    /**
     * Creates new hosts.
     * Corresponds to the "host.create" Zabbix API method.
     *
     * @param params A map of parameters defining the hosts to create (e.g., host, interfaces, groups, templates).
     * @return A JSONObject containing the IDs of the newly created hosts.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONObject create(Map<String, Object> params) throws ZabbixApiException;

    /**
     * Updates existing hosts.
     * Corresponds to the "host.update" Zabbix API method.
     *
     * @param params A map of parameters defining the updates for the hosts (e.g., hostid, status, inventory).
     * @return A JSONObject containing the IDs of the updated hosts.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONObject update(Map<String, Object> params) throws ZabbixApiException;

    /**
     * Deletes hosts.
     * Corresponds to the "host.delete" Zabbix API method.
     * The Zabbix API expects a list of host IDs directly as parameters.
     *
     * @param hostIds A list of host IDs to delete.
     * @return A JSONObject containing the IDs of the deleted hosts.
     * @throws ZabbixApiException if an error occurs during the API call.
     */
    JSONObject delete(List<String> hostIds) throws ZabbixApiException; // Changed to JSONObject as per common Zabbix delete responses
}
