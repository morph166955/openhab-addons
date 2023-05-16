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
package org.openhab.binding.rachio.internal.api.json;

import java.util.HashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.rachio.internal.api.RachioApiResult;
import org.openhab.binding.rachio.internal.api.json.RachioApiGsonDTO.RachioEventProperty;
import org.openhab.binding.rachio.internal.api.json.RachioApiGsonDTO.RachioZoneStatus;
import org.openhab.binding.rachio.internal.api.json.RachioDeviceGsonDTO.RachioCloudNetworkSettings;

/**
 * {@link RachioEventGsonDTO} maps the API result into a Java object (using GSon).
 *
 * @author Markus Michels - Initial contribution
 */
@NonNullByDefault
public class RachioEventGsonDTO {
    public String externalId = "";
    public String routingId = "";
    public String connectId = "";
    public String correlationId = "";
    public String scheduleId = "";
    public String deviceId = "";
    public String zoneId = "";
    public String id = "";

    public String timeZone = "";
    public String timestamp = "";
    public String timeForSummary = "";
    public String startTime = "";
    public String endTime = "";

    public long eventDate = -1;
    public long createDate = -1;
    public long lastUpdateDate = -1;
    public int sequence = -1;
    public String status = ""; // COLD_REBOOT: "status" :
                               // "coldReboot",

    /*
     * type : DEVICE_STATUS
     *
     * Subtype:
     *
     * OFFLINE
     * ONLINE
     * OFFLINE_NOTIFICATION
     * COLD_REBOOT
     * SLEEP_MODE_ON
     * SLEEP_MODE_OFF
     * BROWNOUT_VALVE
     * RAIN_SENSOR_DETECTION_ON
     * RAIN_SENSOR_DETECTION_OFF
     * RAIN_DELAY_ON
     * RAIN_DELAY_OFF
     *
     * Type : SCHEDULE_STATUS
     *
     * Subtype:
     *
     * SCHEDULE_STARTED
     * SCHEDULE_STOPPED
     * SCHEDULE_COMPLETED
     * WEATHER_INTELLIGENCE_NO_SKIP
     * WEATHER_INTELLIGENCE_SKIP
     * WEATHER_INTELLIGENCE_CLIMATE_SKIP
     * WEATHER_INTELLIGENCE_FREEZE
     *
     * Type : ZONE_STATUS
     *
     * Subtype:
     *
     * ZONE_STARTED
     * ZONE_STOPPED
     * ZONE_COMPLETED
     * ZONE_CYCLING
     * ZONE_CYCLING_COMPLETED
     *
     * Type : DEVICE_DELTA
     * Subtype : DEVICE_DELTA
     *
     * Type : ZONE_DELTA
     * Subtype : ZONE_DELTA
     *
     * Type : SCHEDULE_DELTA
     * Subtype : SCHEDULE_DELTA
     */
    public String type = "";
    public String subType = "";
    public String eventType = "";
    public String category = "";
    public String topic = "";
    public String action = "";
    public String summary = "";
    public String description = "";
    public String title = "";
    public String pushTitle = "";

    public String icon = "";
    public String iconUrl = "";

    // ZONE_STATUS
    public Integer zoneNumber = 0;
    public String zoneName = "";
    public Integer zoneCurrent = 0;
    public String zoneRunState = "";
    public Integer duration = 0;
    public Integer durationInMinutes = 0;
    public Integer flowVolume = 0;
    @Nullable
    public RachioZoneStatus zoneRunStatus;

    // SCHEDULE_STATUS
    public String scheduleName = "";
    public String scheduleType = "";

    // COLD_REBOOT
    public String deviceName = ""; // "deviceName" : "My
                                   // Rachio",
    @Nullable
    public RachioCloudNetworkSettings network; // "network" : {}
    String pin = "";

    public RachioApiResult apiResult = new RachioApiResult();

    // public JsonArray eventDatas;
    @Nullable
    public HashMap<String, String> eventParms;
    @Nullable
    public HashMap<String, RachioEventProperty> deltaProperties;

    public RachioEventGsonDTO() {
    }
}
