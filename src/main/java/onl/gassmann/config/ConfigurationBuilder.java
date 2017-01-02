package onl.gassmann.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Created by Henrik Gaßmann on 2016-12-15.
 */
public class ConfigurationBuilder
{
    private final ArrayList<ConfigurationSource> sources = new ArrayList<>();
    private final HashMap<String, OptionDefinition> options = new HashMap<>();

    public ConfigurationBuilder source(ConfigurationSource source)
    {
        if (source == null)
        {
            // todo throw proper exception
            throw new NullPointerException("source == null");
        }
        sources.add(source);
        return this;
    }

    public ConfigurationBuilder option(UnaryOperator<OptionBuilder> defintion)
    {
        final OptionDefinition optionDefinition
                = defintion.apply(new OptionBuilder())
                .build();

        final String name = optionDefinition.name();
        if (options.containsKey(name))
        {
            // todo throw proper exception
            throw new RuntimeException(
                    "An option with the name [" + name + "] has already been added.");
        }

        options.put(name, optionDefinition);

        return this;
    }

    public Configuration build()
    {
        final Map<String, String> rawOptions = new HashMap<>();
        // merge all option maps
        sources.stream()
                .map(ConfigurationSource::get)
                .forEachOrdered(rawOptions::putAll);

        // use of undefined options?
        List<String> unknownOptions = rawOptions.entrySet()
                .stream()
                .map(Map.Entry::getKey)
                .filter(opt -> !options.containsKey(opt))
                .collect(Collectors.toList());
        if (!unknownOptions.isEmpty())
        {
            // todo throw proper exception
            throw new RuntimeException("one or more invalid option/s specified");
        }

        // are there any required options not satisfied?
        List<String> unmetRequirements = options.entrySet()
                .stream()
                .map(Map.Entry::getValue)
                .filter(OptionDefinition::required)
                .map(OptionDefinition::name)
                .filter(name -> !rawOptions.containsKey(name))
                .collect(Collectors.toList());
        if (!unmetRequirements.isEmpty())
        {
            // todo throw proper exception
            throw new RuntimeException("one or more required options haven't been specified");
        }

        // add default values
        options.entrySet()
                .stream()
                .map(Map.Entry::getValue)
                .filter(def -> !def.required())
                .forEach(def -> rawOptions.putIfAbsent(def.name(), def.defaultValue()));

        final HashMap<String, String> stringOptions = new HashMap<>();
        final HashMap<String, Integer> intOptions = new HashMap<>();
        final HashMap<String, Boolean> boolOptions = new HashMap<>();

        for (Map.Entry<String, String> e : rawOptions.entrySet())
        {
            final String key = e.getKey();
            final String raw = e.getValue();
            final OptionDefinition def = options.get(key);

            switch (def.type())
            {
                case STRING:
                    stringOptions.put(key, raw);
                    break;
                case BOOL:
                    boolOptions.put(key, Boolean.valueOf(raw));
                    break;
                case INT:
                    try
                    {
                        intOptions.put(key, Integer.valueOf(raw));
                    }
                    catch (NumberFormatException exc)
                    {
                        // todo throw proper exception
                        throw new RuntimeException("the option [" + key + "] needs to be a valid integer (you specified: " + raw + ")");
                    }
                    break;
            }
        }

        return new Configuration()
        {
            @Override
            public String getRaw(String optionKey)
            {
                return rawOptions.get(optionKey);
            }

            @Override
            public String getString(String optionKey)
            {
                String tmp = stringOptions.get(optionKey);
                if (tmp == null)
                {
                    throw new IllegalArgumentException("the specified option isn't of type String");
                }
                return tmp;
            }

            @Override
            public boolean getBool(String optionKey)
            {
                Boolean tmp = boolOptions.get(optionKey);
                if (tmp == null)
                {
                    throw new IllegalArgumentException("the specified option isn't of type boolean");
                }
                return tmp;
            }

            @Override
            public int getInt(String optionKey)
            {
                Integer tmp = intOptions.get(optionKey);
                if (tmp == null)
                {
                    throw new IllegalArgumentException("the specified option isn't of type int");
                }
                return tmp;
            }
        };
    }

    public String buildHelpMessage()
    {
        // todo implement console friendly help message builder
        return "";
    }
}
