package onl.gassmann.textboard.server;


import onl.gassmann.config.*;
import onl.gassmann.textboard.server.database.DbContext;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Created by henrik on 2016-12-15.
 */
public class Program
{
    private static final Logger LOGGER = Logger.getLogger(Program.class.getName());

    private static final String OPT_PORT = "port";
    private static final String OPT_DATABASE = "database_directory";

    public static void main(String[] args)
    {
        setupLogging();
        Configuration config = configure(args);

        int returnCode = 0;
        TextboardServer srv;
        try
        {
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

            int port = config.getInt(OPT_PORT);
            if (port < 0 || port > 65535)
            {
                throw new ExecutionAbortedException(
                        "The option [port] must be an integer in the interval [0, 65535]. Actual value: " + port);
            }

            srv = new TextboardServer(db);
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
                        .description("The path to the database directory."));

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

    private static void setupLogging()
    {
        Logger global = Logger.getGlobal();
        global.setLevel(Level.INFO);

        SimpleFormatter fileFormatter = new SimpleFormatter();
        FileHandler logFileHandler;
        try
        {
            logFileHandler = new FileHandler("server.log");
            logFileHandler.setLevel(Level.INFO);
            logFileHandler.setFormatter(fileFormatter);
            global.addHandler(logFileHandler);
        }
        catch (IOException e)
        {
            global.severe("Failed to open logfile; details:\n" + e.toString());
        }
    }
}
