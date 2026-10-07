package org.entcore.common.user.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public class BackgroundPreference implements Preference {

    private String background;

    public BackgroundPreference() {
        //for jackson
    }

    public BackgroundPreference(String background) {
        this.background = background;
    }

    @JsonValue
    public String getBackground() {
        return background;
    }

    @JsonCreator
    public BackgroundPreference setBackground(String background) {
        this.background = background;
        return this;
    }

    @Override
    public String encode() {
        return background;
    }
}