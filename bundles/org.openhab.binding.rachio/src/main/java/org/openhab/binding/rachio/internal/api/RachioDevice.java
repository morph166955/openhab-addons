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

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.rachio.internal.RachioBindingConstants;
import org.openhab.binding.rachio.internal.api.json.RachioDeviceGsonDTO.RachioCloudDevice;
import org.openhab.binding.rachio.internal.api.json.RachioDeviceGsonDTO.RachioCloudNetworkSettings;
import org.openhab.binding.rachio.internal.api.json.RachioZoneGsonDTO.RachioCloudZone;
import org.openhab.binding.rachio.internal.handler.RachioDeviceHandler;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RachioDevice} provides device level functions.
 *
 * @author Markus Michels - Initial contribution
 */
@NonNullByDefault
public class RachioDevice extends RachioCloudDevice {
    private final Logger logger = LoggerFactory.getLogger(RachioDevice.class);

    // extensions to cloud attributes
    public String runList = "";
    public Integer runTime = 0;
    public String lastEvent = "";
    @Nullable
    public DateTimeType lastEventTime;
    public boolean paused = false;
    public int rainDelay = 0;

    @Nullable
    public ThingUID bridgeUID;
    @Nullable
    public ThingUID devUID;
    private HashMap<String, RachioZone> zoneList = new HashMap<String, RachioZone>();
    @Nullable
    private RachioDeviceHandler thingHandler = null;
    @Nullable
    public RachioCloudNetworkSettings network;
    public String scheduleName = "";

    @SuppressWarnings("unused")
    public RachioDevice(RachioCloudDevice device) {
        try {
            RachioApi.copyMatchingFields(device, this);
            logger.trace("Adding ddevice '{}' (id='{}', model='{}', on={}, status={}, deleted={})", device.name,
                    device.id, device.model, device.on, device.status, device.deleted);
            if (!device.deleted) {
                zoneList = new HashMap<String, RachioZone>(); // discard current list
                for (int i = 0; i < device.zones.size(); i++) {
                    RachioCloudZone zone = device.zones.get(i);
                    if (true /* zone.enabled */) {
                        zoneList.put(zone.id, new RachioZone(zone, getThingID()));
                    } else {
                        logger.trace("Zone '{}.{}[{}]' is disabled, skip.", device.name, zone.name, zone.zoneNumber);
                    }
                }
            }
        } catch (RuntimeException e) {
            logger.warn("Unable to initialize device '{}': {}", device.name, e.getMessage());
        }
    }

    /**
     * Set the ThingHandler for this device
     *
     * @param deviceHandler
     */
    public void setThingHandler(RachioDeviceHandler deviceHandler) {
        thingHandler = deviceHandler;
    }

    /**
     * @return thing handler for this zone
     */
    @Nullable
    public RachioDeviceHandler getThingHandler() {
        return thingHandler;
    }

    /**
     * compare some specific device properties to decide if channel updates are performed
     *
     * @param cdev device properties to compare
     * @return true: no change, false: update required
     */
    public boolean compare(@Nullable RachioDevice cdev) {
        if ((cdev == null) || !id.equalsIgnoreCase(cdev.id) || !status.equalsIgnoreCase(cdev.status) || (on != cdev.on)
                || (paused != cdev.paused)) {
            logger.trace("Device data was updated");
            return false;
        }
        return true;
    }

    /**
     * Copy relevant attributes read from cloud
     *
     * @param updatedData new device settings received from cloud call
     */
    public void update(@Nullable RachioDevice updatedData) {
        if ((updatedData == null) || !id.equals(updatedData.id)) {
            return;
        }
        status = updatedData.status;
        on = updatedData.on;
        paused = updatedData.paused;
    }

    /**
     * Save ThingUID (used for mapping ThingUID to internal data structure)
     *
     * @param bridgeUID
     * @param deviceUID
     */
    public void setUID(ThingUID bridgeUID, ThingUID deviceUID) {
        this.bridgeUID = bridgeUID;
        devUID = deviceUID;
    }

    /**
     * @return Device thing uid
     */
    @Nullable
    public ThingUID getUID() {
        return devUID;
    }

    /**
     * Fill the Thing property data
     *
     * @return A map for key/value
     */
    public Map<String, String> fillProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put(Thing.PROPERTY_VENDOR, RachioBindingConstants.BINDING_VENDOR);
        properties.put(PROPERTY_NAME, name);
        properties.put(PROPERTY_MODEL, model);
        properties.put(Thing.PROPERTY_SERIAL_NUMBER, serialNumber);
        properties.put(Thing.PROPERTY_MAC_ADDRESS, macAddress);
        properties.put(PROPERTY_DEV_ID, id);
        properties.put(PROPERTY_DEV_LAT, new Double(latitude).toString());
        properties.put(PROPERTY_DEV_LONG, new Double(longitude).toString());
        RachioCloudNetworkSettings nw = network;
        if (nw != null) {
            properties.put(PROPERTY_IP_ADDRESS, nw.ip);
            properties.put(PROPERTY_IP_MASK, nw.ip);
            properties.put(PROPERTY_IP_GW, nw.gw);
            properties.put(PROPERTY_IP_DNS1, nw.dns1);
            properties.put(PROPERTY_IP_DNS2, nw.dns2);
            properties.put(PROPERTY_WIFI_RSSI, nw.rssi);
        }
        return properties;
    }

    /**
     * Get the thing unique id
     *
     * @return Suffix for the thing name
     */
    public String getThingID() {
        return macAddress;
    }

    /**
     * Get the thing's name
     *
     * @return Name
     */
    public String getThingName() {
        return name;
    }

    /**
     * Get controller status as OnOffType
     *
     * @return Thing status
     */
    public ThingStatus getStatus() {
        if (status.equals("ONLINE")) {
            return ThingStatus.ONLINE;
        }
        if (status.equals("OFFLINE")) {
            return ThingStatus.OFFLINE;
        }
        logger.debug("Device status '{}' was mapped to OFFLINE", status);
        return ThingStatus.OFFLINE;
    }

    public void setStatus(String new_status) {
        if (new_status.equals("ONLINE") || new_status.equals("OFFLINE")) {
            status = new_status;
            return;
        }
        logger.debug("Device status '{}' was not set!", new_status);
    }

    /**
     * Get controller status (online/offline) as OnOffType
     *
     * @return Controller status, ON=online, OFF=offline
     */
    public OnOffType getOnline() {
        return status.equals("ONLINE") ? OnOffType.ON : OnOffType.OFF;
    }

    /**
     * Get enabled status as OnOffType
     *
     * @return ON=enabled, OFF=disabled
     */
    public OnOffType getEnabled() {
        return on ? OnOffType.ON : OnOffType.OFF;
    }

    /**
     * Get operation mode
     *
     * @return ON=running, OFF=standby
     */
    public OnOffType getSleepMode() {
        return paused ? OnOffType.ON : OnOffType.OFF;
    }

    public void setSleepMode(String subType) {
        paused = subType.contains("ON") ? true : false;
    }

    /**
     * Put controller into rain delay mode
     *
     * @param newDelay Number of seconds for the Rain Delay mode
     */
    public void setRainDelayTime(int newDelay) {
        rainDelay = newDelay;
    }

    /**
     * Get the list of zones to run when starting watering on the controller
     *
     * @return Comma seperated list of zones to run
     */
    public String getRunZones() {
        return runList;
    }

    /**
     * Set the zone list for running the controller
     *
     * @param list Comma seperated list of zone IDs
     */
    public void setRunZones(String list) {
        runList = list;
    }

    /**
     * Get total run time for the controller as returned from the Cloud API
     *
     * @return Total run time for the controller
     */
    public int getRunTime() {
        return runTime;
    }

    /**
     * Set the run time for next run
     *
     * @param time Number of seconds to run the zones
     */
    public void setRunTime(int time) {
        runTime = time;
    }

    public void setEvent(String event, DateTimeType ts) {
        lastEvent = event;
        lastEventTime = ts;
    }

    public String getEvent() {
        return lastEvent;
    }

    public @Nullable DateTimeType getEventTime() {
        return lastEventTime;
    }

    public void setNetwork(@Nullable RachioCloudNetworkSettings network) {
        this.network = network;
    }

    public String getAllRunZonesJson(int defaultRuntime) {
        boolean flAll = runList.isEmpty() || runList.equalsIgnoreCase("ALL");

        String list = runList + ","; // make sure last entry is terminated by ','
        String json = "{ \"zones\" : [";
        for (HashMap.Entry<String, RachioZone> ze : zoneList.entrySet()) {
            @Nullable
            RachioZone zone = ze.getValue();
            if (flAll || (list.contains(zone.zoneNumber + ",") && (zone.getEnabled() == OnOffType.ON))) {
                int runtime = zone.getStartRunTime() > 0 ? zone.getStartRunTime() : defaultRuntime;
                if (json.contains("\"id\"")) {
                    json = json + ", ";
                }
                json = json + "{ \"id\" : \"" + zone.id + "\", \"duration\" : " + runtime + ", \"sortOrder\" : 1}";
            }
        }
        json = json + "] }";
        return json;
    }

    /**
     * Get a list of all zones belonging to this controller
     *
     * @return Zone list (HashMap)
     */
    public HashMap<String, RachioZone> getZones() {
        return zoneList;
    }

    @Nullable
    public RachioZone getZoneByNumber(int zoneNumber) {
        for (HashMap.Entry<String, RachioZone> ze : zoneList.entrySet()) {
            RachioZone zone = ze.getValue();
            if ((zone != null) && zone.zoneNumber == zoneNumber) {
                return zone;
            }
        }
        return null;
    }

    @Nullable
    public RachioZone getZoneById(String zoneId) {
        for (HashMap.Entry<String, RachioZone> ze : zoneList.entrySet()) {
            RachioZone zone = ze.getValue();
            if ((zone != null) && zone.id.equals(zoneId)) {
                return zone;
            }
        }
        return null;
    }
}
