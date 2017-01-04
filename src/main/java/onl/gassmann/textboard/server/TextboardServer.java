package onl.gassmann.textboard.server;

import onl.gassmann.textboard.server.database.DbContext;
import onl.gassmann.textboard.server.database.Topic;

import java.io.IOException;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Logger;

/**
 * Created by gassmann on 2017-01-03.
 */
class TextboardServer
{
    private static final Logger LOGGER = Logger.getLogger(TextboardServer.class.getName());

    private final ServerSocket serverSocket;
    private final Set<ClientConnection> connectedClients
            = new ConcurrentSkipListSet<>(Comparator.comparing(client -> client.id));

    private final DbContext db;

    public TextboardServer(DbContext db)
    {
        if (db == null)
        {
            throw new NullPointerException("db == null");
        }
        this.db = db;
        try
        {
            serverSocket = new ServerSocket();
        }
        catch (IOException exc)
        {
            throw new ExecutionAbortedException(
                    "Failed to open the server socket channel; details:\n" + exc.getMessage());
        }
    }

    public void run(int port)
    {
        try
        {
            serverSocket.bind(new InetSocketAddress(port));
        }
        catch (IOException e)
        {
            throw new ExecutionAbortedException(
                    "Failed to bind the server socket to the specified port; details:\n" + e.getMessage());
        }
        SocketAddress localAddress = serverSocket.getLocalSocketAddress();
        LOGGER.info("Listening on [" + localAddress + "]");

        try
        {
            while (!serverSocket.isClosed())
            {
                Socket clientSocket;
                try
                {
                    // wait until a client connects
                    clientSocket = serverSocket.accept();
                }
                catch (SecurityException exc)
                {
                    LOGGER.warning("A server socket accept call failed due to a security exception; details:\n"
                                           + exc);
                    continue;
                }
                catch (SocketTimeoutException exc)
                {
                    // this shouldn't ever happen
                    LOGGER.warning("A server socket accept call timed out unexpectedly.");
                    continue;
                }
                // todo separate client connection creation failure from thread creation failure
                try
                {
                    // create a connection handler
                    ClientConnection client = new ClientConnection(clientSocket, this);

                    // create a new thread for the connection
                    new Thread(client, "Client" + client.getRemoteAddress())
                            .start();

                    connectedClients.add(client);
                }
                catch (RuntimeException exc)
                {
                    LOGGER.warning("Failed to create a client connection; details:\n" + exc);
                }
            }
        }
        catch (SocketException exc)
        {
            if (!serverSocket.isClosed())
            {
                LOGGER.severe("The server socket accept failed with a socket exception; details:\n" + exc);
            }
        }
        catch (IOException exc)
        {
            LOGGER.severe("The server socket accept failed with an io exception; details:\n" + exc);
        }
        finally
        {
            // try to gracefully shutdown as many client connections as possible
            for (Iterator<ClientConnection> it = connectedClients.iterator(); it.hasNext(); )
            {
                ClientConnection client = it.next();
                if (client != null)
                {
                    try
                    {
                        client.close();
                    }
                    catch (IOException exc)
                    {
                        LOGGER.warning("Failed to close a client connection during server stop; details:\n" + exc);
                        it.remove();
                    }
                }
            }
        }
    }

    public void stop()
    {
        try
        {
            serverSocket.close();
        }
        catch (IOException exc)
        {
            LOGGER.warning("Failed to close the server socket during stop operation; details:\n" + exc.getMessage());
        }
        for (ClientConnection client : connectedClients)
        {
            try
            {
                client.close();
            }
            catch (IOException e)
            {
                LOGGER.warning("Failed to shutdown a client connection during server stop; details:\n" + e);
            }
        }
    }

    public void notifyConnectionClosed(ClientConnection client)
    {
        if (!client.isClosed())
        {
            // todo proper exception throwing
            throw new RuntimeException("client isn't closed");
        }
        connectedClients.remove(client);
    }

    public void addNewMessage(String[] lines)
    {
        Topic updated = db.put(lines);
        for (ClientConnection client : connectedClients)
        {
            client.notifyTopicChanged(updated);
        }
    }

    public DbContext getDb()
    {
        return db;
    }

    public Charset charset()
    {
        return Charset.defaultCharset();
    }
}
