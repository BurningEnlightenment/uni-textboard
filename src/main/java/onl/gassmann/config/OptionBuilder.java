package onl.gassmann.config;

/**
 * Created by Henrik Gaßmann on 2016-12-15.
 */
public final class OptionBuilder
{
    private String name;
    private String description;

    private OptionType type = OptionType.STRING;
    private String defaultValue;

    OptionBuilder()
    {
    }

    public OptionBuilder name(String name)
    {
        if (name.contains("-") || name.contains(" ") || name.contains("="))
        {
            // todo throw proper excxeption
            throw new RuntimeException("invalid option name: " + name);
        }
        this.name = name;
        return this;
    }

    public OptionBuilder description(String description)
    {
        this.description = description;
        return this;
    }

    public OptionBuilder defaultValue(String defaultValue)
    {
        this.defaultValue = defaultValue;
        return this;
    }

    public OptionBuilder type(OptionType type)
    {
        this.type = type;
        return this;
    }


    OptionDefinition build()
    {
        // todo option validation etc.
        return new OptionDefinition()
        {
            final String name_ = name;
            final String description_ = description;

            final OptionType type_ = type;

            final String defaultValue_ = defaultValue;
            final boolean required_ = defaultValue == null;

            @Override
            public String name()
            {
                return name_;
            }

            @Override
            public String description()
            {
                return description_;
            }

            @Override
            public OptionType type()
            {
                return type_;
            }

            @Override
            public boolean required()
            {
                return required_;
            }

            @Override
            public String defaultValue()
            {
                return defaultValue_;
            }
        };
    }
}
