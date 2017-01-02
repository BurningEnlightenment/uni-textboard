package onl.gassmann.config;

/**
 * Created by Henrik Gaßmann on 2016-12-15.
 */
public interface Configuration
{
    String getRaw(String optionKey);

    String getString(String optionKey);
    boolean getBool(String optionKey);
    int getInt(String optionKey);
}
