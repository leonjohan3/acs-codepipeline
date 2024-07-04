package org.example;

import lombok.experimental.UtilityClass;

@UtilityClass
@SuppressWarnings("PMD.DataClass")
public class ServiceConstants {

    public static final String CONFIG_GRP_PATTERN = "^[a-zA-Z0-9]{3}$";
    public static final String CONFIG_GRP_MESSAGE = "configuration-group-prefix must be letters or digits and must have a length of 3";
}
