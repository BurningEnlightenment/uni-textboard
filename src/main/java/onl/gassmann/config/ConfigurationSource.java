package onl.gassmann.config;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Henrik Gaßmann on 2016-12-15.
 */
public abstract class ConfigurationSource
{
    protected final Map<String, String> keyValuePairs = new HashMap<>();

    protected void addKeyValuePair(String keyValuePair)
    {
        int delimiter = keyValuePair.indexOf('=');

        if (delimiter < 0)
        {
            // todo throw proper exception
            throw new RuntimeException("Found an option without a value delimiter (=): " + keyValuePair);
        }
        else if (delimiter == 0)
        {
            // todo throw proper exception
            throw new RuntimeException("Found value specification without an option key: " + keyValuePair);
        }

        String key = keyValuePair.substring(0, delimiter);
        // if the last argument character is our delimiter we insert an empty string.
        String value = delimiter + 1 == keyValuePair.length() ? "" : keyValuePair.substring(delimiter+1);

        if (keyValuePairs.containsKey(key))
        {
            // todo throw proper exception
            throw new RuntimeException("option occurred twice: " + keyValuePair);
        }
        keyValuePairs.put(key, value);
    }

    Map<String, String> get()
    {
        return keyValuePairs;
    }
}
