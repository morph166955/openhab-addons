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
package org.openhab.binding.androidtv.internal.protocol.philipstv.service.model.ambilight;

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@link AmbilightLoungeDto} class defines the Data Transfer Object
 * for the Philips TV API /ambilight/lounge endpoint to power on or off the ambilight lounge mode.
 *
 * @author Benjamin Meyer - Initial contribution
 * @author Ben Rosenblum - Merged into AndroidTV
 */
@NonNullByDefault
public class AmbilightLoungeDto {

    @JsonProperty("color")
    private AmbilightColorDto color;

    public AmbilightLoungeDto(AmbilightColorDto color) {
        this.color = color;
    }

    public void setColor(AmbilightColorDto color) {
        this.color = color;
    }

    public AmbilightColorDto getColor() {
        return color;
    }

    @Override
    public String toString() {
        return "AmbilightLoungeDto{" + "color = '" + color + '\'' + "}";
    }
}
