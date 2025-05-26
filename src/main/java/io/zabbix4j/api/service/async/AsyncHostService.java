package io.zabbix4j.api.service.async;

import io.zabbix4j.api.exception.ZabbixApiException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous service interface for Zabbix Host related API operations.
 * All methods return a {@link CompletableFuture} to handle asynchronous responses.
 *
 * @author CSJ
 */
public interface AsyncHostService {

    /**
     * Asynchronously retrieves hosts based on the provided parameters.
     * Corresponds to the "host.get" Zabbix API method.
     *
     * @param params A map of parameters for the "host.get" method (e.g., filter, output, selectInterfaces).
     * @return A {@link CompletableFuture} which will complete with a JSONArray containing the retrieved host objects,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONArray> get(Map<String, Object> params);

    /**
     * Asynchronously creates new hosts.
     * Corresponds to the "host.create" Zabbix API method.
     *
     * @param params A map of parameters defining the hosts to create (e.g., host, interfaces, groups, templates).
     * @return A {@link CompletableFuture} which will complete with a JSONObject containing the IDs of the newly created hosts,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONObject> create(Map<String, Object> params);

    /**
     * Asynchronously updates existing hosts.
     * Corresponds to the "host.update" Zabbix API method.
     *
     * @param params A map of parameters defining the updates for the hosts (e.g., hostid, status, inventory).
     * @return A {@link CompletableFuture} which will complete with a JSONObject containing the IDs of the updated hosts,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONObject> update(Map<String, Object> params);

    /**
     * Asynchronously deletes hosts.
     * Corresponds to the "host.delete" Zabbix API method.
     * The Zabbix API expects a list of host IDs directly as parameters.
     *
     * @param hostIds A list of host IDs to delete.
     * @return A {@link CompletableFuture} which will complete with a JSONObject containing the IDs of the deleted hosts,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<JSONObject> delete(List<String> hostIds);
}
