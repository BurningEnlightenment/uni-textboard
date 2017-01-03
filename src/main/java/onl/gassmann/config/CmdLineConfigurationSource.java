package onl.gassmann.config;

/**
 * Created by Henrik Gaßmann on 2016-12-15.
 */
public final class CmdLineConfigurationSource
        extends ConfigurationSource
{
    public CmdLineConfigurationSource(String[] cmdLineArgs)
    {
        for (String arg : cmdLineArgs)
        {
            if (arg == null || arg.length() < 1)
            {
                continue;
            }
            int length = arg.length();
            if (length <3 || !arg.startsWith("--"))
            {
                // todo throw proper exception
                throw new RuntimeException("invalid option syntax: " + arg);
            }

            addKeyValuePair(arg.substring(2));
        }
    }
}
