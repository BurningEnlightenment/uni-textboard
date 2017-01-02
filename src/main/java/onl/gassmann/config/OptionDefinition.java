package onl.gassmann.config;

/**
 * Created by Henrik Gaßmann on 2016-12-15.
 */
interface OptionDefinition
{
    String name();
    String description();

    OptionType type();

    boolean required();
    String defaultValue();
}
