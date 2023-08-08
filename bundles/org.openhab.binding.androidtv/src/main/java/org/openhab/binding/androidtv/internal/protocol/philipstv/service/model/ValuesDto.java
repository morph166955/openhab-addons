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
package org.openhab.binding.androidtv.internal.protocol.philipstv.service.model;

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Part of {@link TvSettingsUpdateDto}
 *
 * @author Benjamin Meyer - Initial contribution
 * @author Ben Rosenblum - Merged into AndroidTV
 */
@NonNullByDefault
public class ValuesDto {

    @JsonProperty
    private ValueDto value;

    public ValuesDto(ValueDto value) {
        this.value = value;
    }

    public void setValue(ValueDto value) {
        this.value = value;
    }

    public ValueDto getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "ValuesItem{" + "value = '" + value + '\'' + "}";
    }
}
