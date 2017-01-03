package onl.gassmann.textboard.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * Created by gassmann on 2017-01-03.
 */
public class ClientConnection implements Runnable, Closeable
{
    private static final Logger LOGGER = Logger.getLogger(TextboardServer.class.getName());

    private final TextboardServer owner;
    private final Socket connection;
    private final String remoteAddress;

    public ClientConnection(Socket connection, TextboardServer owner)
    {
        if (owner == null)
        {
            throw new NullPointerException("owner == null");
        }
        this.owner = owner;
        if (connection == null)
        {
            throw new NullPointerException("connection == null");
        }
        this.connection = connection;

        remoteAddress = connection.getRemoteSocketAddress()
                .toString();
        LOGGER.info("A new client connected from " + remoteAddress);
    }

    @Override
    public void run()
    {

    }

    @Override
    public void close() throws IOException
    {

    }

    public boolean isClosed()
    {
        return connection.isClosed();
    }

    public String getRemoteAddress()
    {
        return remoteAddress;
    }
}
