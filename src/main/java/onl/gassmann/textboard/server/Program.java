package onl.gassmann.textboard.server;


import onl.gassmann.config.*;
import onl.gassmann.textboard.server.database.DbContext;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Created by henrik on 2016-12-15.
 */
public class Program
{
    private static final Logger LOGGER = Logger.getLogger(Program.class.getName());

    private static final String OPT_PORT = "port";
    private static final String OPT_DATABASE = "database_directory";
    private static final String OPT_CHARSET = "charset";

    public static void main(String[] args)
    {
        Configuration config = configure(args);

        int returnCode = 0;
        TextboardServer srv;
        try
        {
            // load existing message database from configured file
            DbContext db;
            Path dbPath = Paths.get(config.getString(OPT_DATABASE));
            try
            {
                db = new DbContext(dbPath);
            }
            catch (RuntimeException e)
            {
                throw new ExecutionAbortedException("Failed to read the message database from " + dbPath, e);
            }

            // validate the port option
            int port = config.getInt(OPT_PORT);
            if (port < 0 || port > 65535)
            {
                throw new ExecutionAbortedException(
                        "The option [port] must be an integer in the interval [0, 65535]. Actual value: " + port);
            }

            // retrieve the configured network character encoding
            Charset charset;
            try
            {
                charset = Charset.forName(config.getString(OPT_CHARSET));
            }
            catch (IllegalCharsetNameException | UnsupportedCharsetException e)
            {
                String value = config.getString(OPT_CHARSET);
                throw new ExecutionAbortedException("The configured charset \"" + value + "\" could not be loaded", e);
            }

            // instantiate and run the server
            srv = new TextboardServer(db, charset);
            srv.run(port);
        }
        catch (ExecutionAbortedException e)
        {
            LOGGER.severe(e.getMessage() + "\n\nexiting...");
            returnCode = -1;
        }
        catch (RuntimeException e)
        {
            LOGGER.severe("unhandled exception:\n" + e + "\n\nexiting...");
            returnCode = -1;
        }
        System.exit(returnCode);
    }

    /**
     * Builds the configuration from command line arguments and the configuration file.
     * If I had known that java has sufficient utilities to parse .properties files I probably would have used
     * that instead :/
     */
    private static Configuration configure(String[] args)
    {
        // define configuration options
        ConfigurationBuilder configBuilder = new ConfigurationBuilder()
                .option(b -> b.name(OPT_PORT)
                        .type(OptionType.INT)
                        .defaultValue("4242")
                        .description("The port on which the server listens."))
                .option(b -> b.name(OPT_DATABASE)
                        .type(OptionType.STRING)
                        .defaultValue("")
                        .description("The path to the database directory."))
                .option(b -> b.name(OPT_CHARSET)
                        .type(OptionType.STRING)
                        .defaultValue(Charset.defaultCharset().name())
                        .description("The charset used to encode the network traffic."));

        // add configuration option sources
        try
        {
            configBuilder.source(new FileConfigurationSource(Paths.get("server.cfg")));
        }
        catch (RuntimeException e)
        {
            // todo print further failure information and help message
            LOGGER.severe("failed to parse configuration file; details:\n" + e);
            System.exit(-1);
        }
        try
        {
            configBuilder.source(new CmdLineConfigurationSource(args));
        }
        catch (RuntimeException e)
        {
            // todo print further failure information and help message
            LOGGER.severe("failed to parse command line arguments; details:\n" + e);
            System.exit(-1);
        }

        // assemble configuration
        try
        {
            return configBuilder.build();
        }
        catch (RuntimeException e)
        {
            // todo print further failure information and help message
            LOGGER.severe("configBuilder.build() failed; details:\n" + e);
            System.exit(-1);
            // this is essentially dead code, because we obviously either return the build
            // configuration or exit with an error code, but javac doesn't know...
            throw e;
        }
    }
}
