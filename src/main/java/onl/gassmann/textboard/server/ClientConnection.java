package onl.gassmann.textboard.server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;
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

    private final PrintWriter out;
    private final BufferedReader in;

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

        Charset charset = owner.charset();

        // create a correctly encoding PrintWriter
        try
        {
            OutputStream outStream = connection.getOutputStream();
            OutputStreamWriter encodingWriter = new OutputStreamWriter(outStream, charset);
            out = new PrintWriter(encodingWriter, false);
        }
        catch (IOException exc)
        {
            // todo proper exception throwing
            throw new RuntimeException("Failed to create a print writer for connection " + remoteAddress + ".", exc);
        }

        // create a correctly decoding BufferedReader
        try
        {
            InputStream inStream = connection.getInputStream();
            InputStreamReader decodingReader = new InputStreamReader(inStream, charset);
            in = new BufferedReader(decodingReader);
        }
        catch (IOException exc)
        {
            // todo proper exception throwing
            throw new RuntimeException("Failed to create a buffered reader for connection " + remoteAddress + ".", exc);
        }
    }

    @Override
    public void run()
    {

    }

    @Override
    public void close() throws IOException
    {
        connection.close();
        owner.notifyConnectionClosed(this);
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
