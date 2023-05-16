/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.rachio.internal.api;

import static org.openhab.binding.rachio.internal.RachioBindingConstants.*;

import java.io.IOException;
import java.util.Map;
import java.util.Scanner;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.rachio.internal.RachioHandlerFactory;
import org.openhab.binding.rachio.internal.api.json.RachioEventGsonDTO;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * {@link RachioWebHookServlet} implements the callback for the Rachio Cloud event API.
 *
 * @author Markus Michels - Initial contribution
 */
@Component(service = HttpServlet.class, configurationPolicy = ConfigurationPolicy.OPTIONAL, immediate = true)
@NonNullByDefault
public class RachioWebHookServlet extends HttpServlet {
    private static final long serialVersionUID = -4654253998990066051L;
    private final Logger logger = LoggerFactory.getLogger(RachioWebHookServlet.class);
    private final Gson gson = new Gson();

    private final HttpService httpService;
    private final RachioHandlerFactory rachioHandlerFactory;

    /**
     * OSGi activation callback.
     *
     * @param config Service config.
     */
    @Activate
    public RachioWebHookServlet(@Reference HttpService httpService,
            @Reference RachioHandlerFactory rachioHandlerFactory, Map<String, Object> config) {
        this.httpService = httpService;
        this.rachioHandlerFactory = rachioHandlerFactory;
        try {
            httpService.registerServlet(SERVLET_WEBHOOK_PATH, this, null, httpService.createDefaultHttpContext());
            logger.debug("RchioWebhook: Started servlet at {}", SERVLET_WEBHOOK_PATH);
        } catch (ServletException | NamespaceException e) {
            logger.warn("RchioWebhook: Could not start Rachio Webhook servlet", e);
        }
    }

    /**
     * OSGi deactivation callback.
     */
    @Deactivate
    protected void deactivate() {
        httpService.unregister(SERVLET_WEBHOOK_PATH);
        logger.debug("RachioWebHook: Servlet stopped");
    }

    @Override
    protected void service(@Nullable HttpServletRequest request, @Nullable HttpServletResponse resp)
            throws ServletException, IOException {
        if (request == null) {
            return;
        }

        String data = inputStreamToString(request);
        try {
            String ipAddress = request.getHeader("HTTP_X_FORWARDED_FOR");
            if (ipAddress == null) {
                ipAddress = request.getRemoteAddr();
            }
            String path = request.getRequestURI();

            logger.trace("RachioWebhook: Reqeust from {}:{}{} ({}:{}, {})", ipAddress, request.getRemotePort(), path,
                    request.getRemoteHost(), request.getServerPort(), request.getProtocol());
            if (!path.equalsIgnoreCase(SERVLET_WEBHOOK_PATH)) {
                logger.debug("RachioWebHook: Invalid request received - path = {}", path);
                return;
            }

            // Fix malformed API v3 Event JSON
            data = data.replace("\"{", "{");
            data = data.replace("}\"", "}");
            data = data.replace("\\", "");
            data = data.replace("\"?\"", "'?'"); // fix json for"summary" : "<Device> has turned off and back on.
                                                 // This
                                                 // is usually not a problem. If power cycles continue, tap "?"
                                                 // above to
                                                 // contact Rachio Support.",

            logger.trace("RachioWebHook: Data='{}'", data);
            RachioEventGsonDTO event = gson.fromJson(data, RachioEventGsonDTO.class);
            if (event != null) {
                logger.trace("RachioEvent {}.{} for device '{}': {}", event.category, event.type, event.deviceId,
                        event.summary);

                event.apiResult.setRateLimit(request.getHeader(RACHIO_JSON_RATE_LIMIT),
                        request.getHeader(RACHIO_JSON_RATE_REMAINING), request.getHeader(RACHIO_JSON_RATE_RESET));

                if (!rachioHandlerFactory.webHookEvent(ipAddress, event)) {
                    logger.debug("RachioWebHook: Event-JSON='{}'", data);
                }
                return;
            }
            logger.debug("RachioWebHook: Unable to process inbound request, data='{}'", data);
        } catch (RuntimeException e) {
            logger.debug("RachioWebHook: Exception processing callback: {}, data='{}'", e.getMessage(),
                    data != null ? data : "n/a");
        } finally {
            if (resp != null) {
                setHeaders(resp);
                resp.getWriter().write("");
            }
        }
    }

    @SuppressWarnings("resource")
    private String inputStreamToString(HttpServletRequest request) throws IOException {
        Scanner scanner = new Scanner(request.getInputStream()).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "";
    }

    private void setHeaders(HttpServletResponse response) {
        response.setCharacterEncoding(SERVLET_WEBHOOK_CHARSET);
        response.setContentType(SERVLET_WEBHOOK_APPLICATION_JSON);
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
    }
}
