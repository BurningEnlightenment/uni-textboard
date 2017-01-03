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
        while (!connection.isInputShutdown())
        {
            final String instruction;
            try
            {
                instruction = in.readLine();
            }
            catch (IOException exc)
            {
                // todo read exception handling
                break;
            }
            int length = instruction.length();
            if (length < 1)
            {
                writeError("Got an empty line instead of an instruction.");
            }

            switch (instruction.charAt(0))
            {
                // exit command
                case 'X':
                    if (length != 1)
                    {
                        // X followed by an argument
                        writeError("the exit instruction (X) must not contain any arguments.");
                        break;
                    }
                    LOGGER.info("received the exit command from" + remoteAddress);
                    try
                    {
                        close();
                    }
                    catch (IOException exc)
                    {
                        LOGGER.warning("Failed to close client after receiving the exit command from "
                                               + getRemoteAddress() + "; details:\n" + exc.getMessage());
                    }
                    break;

                // message post command
                case 'P':
                    writeError("the post command is not yet implemented.");
                    break;

                // last changed topic command
                case 'L':
                    writeError("the list command is not yet implemented.");
                    break;

                // get messages by topic
                case 'T':
                    writeError("the topic command is not yet implemented.");
                    break;

                // get all message newer than the specified time point
                case 'W':
                    writeError("the retrieve command is not yet implemented.");
                    break;
            }
        }
        try
        {
            connection.close();
        }
        catch (IOException exc)
        {
            LOGGER.severe("Failed to close the socket for " + getRemoteAddress() + "; details:\n" + exc.getMessage());
        }
        finally
        {
            owner.notifyConnectionClosed(this);
        }
    }

    @Override
    public void close() throws IOException
    {
        connection.shutdownInput();
    }

    public boolean isClosed()
    {
        return connection.isClosed();
    }

    public String getRemoteAddress()
    {
        return remoteAddress;
    }

    private void writeError(String msg)
    {
        out.println("E " + msg);
        out.flush();
    }
}
