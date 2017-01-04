package onl.gassmann.textboard.server;

import onl.gassmann.textboard.server.database.Topic;

import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by gassmann on 2017-01-03.
 */
class ClientConnection implements Runnable, Closeable
{
    private static final Logger LOGGER = Logger.getLogger(TextboardServer.class.getName());
    private static final AtomicInteger ID_PROVIDER = new AtomicInteger();

    public final int id = ID_PROVIDER.getAndIncrement();
    private final TextboardServer owner;
    private final Socket connection;
    private final String remoteAddress;

    private final PrintWriter out;
    private final BufferedReader in;

    private final ConcurrentLinkedQueue<Topic> topicChangeQueue = new ConcurrentLinkedQueue<>();

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
        try
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
                    throw new RuntimeException("Failed to read the next instruction from " + remoteAddress, exc);
                }
                if (instruction.length() < 1)
                {
                    writeError("Got an empty line instead of an instruction.");
                }

                boolean skipTopicUpdate = false;
                switch (instruction.charAt(0))
                {
                    // exit command
                    case 'X':
                        skipTopicUpdate = onExitCommand(instruction);
                        break;

                    // message post command
                    case 'P':
                        onPutCommand(instruction);
                        break;

                    // last changed value command
                    case 'L':
                        onListCommand(instruction);
                        break;

                    // get messages by value
                    case 'T':
                        writeError("the value command is not yet implemented.");
                        break;

                    // get all message newer than the specified time point
                    case 'W':
                        writeError("the retrieve command is not yet implemented.");
                        break;

                    default:
                        writeError("unknown command.");
                        break;
                }
                if (!skipTopicUpdate)
                {
                    writeTopicUpdates();
                }
            }
        }
        catch (RuntimeException e)
        {
            LOGGER.severe(
                    "The connection to " + remoteAddress + " was interrupted by an unrecoverable error; details:\n"
                            + e);
        }
        finally
        {
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
    }

    private boolean onExitCommand(final String instruction)
    {
        if (instruction.length() != 1)
        {
            // X followed by an argument
            writeError("the exit instruction (X) must not contain any arguments.");
            return false;
        }
        LOGGER.info("received the exit command from " + remoteAddress);
        try
        {
            close();
        }
        catch (IOException exc)
        {
            throw new RuntimeException("Failed to gracefully close client after receiving the exit command from "
                                               + remoteAddress, exc);
        }
        return true;
    }

    private void onPutCommand(final String instruction)
    {
        if (instruction.length() != 1)
        {
            // P followed by an argument
            writeError("the put instruction (P) must not contain any arguments.");
            return;
        }

        int numMsgs;
        try
        {
            String numMsgsLine = in.readLine();
            numMsgs = Integer.parseInt(numMsgsLine);
        }
        catch (IOException e)
        {
            throw new RuntimeException(
                    "Failed to read the number of messages after a put command due to an IOException from "
                            + remoteAddress, e);
        }
        catch (NumberFormatException e)
        {
            writeError("The line after the P has to be a positive integer.");
            return;
        }
        if (numMsgs < 0)
        {
            writeError("the number of messages must not be a negative number.");
            return;
        }

        // receive messages
        for (int i = 0; i < numMsgs; ++i)
        {
            int numLines;
            try
            {
                String numLinesLine = in.readLine();
                numLines = Integer.parseInt(numLinesLine);
            }
            catch (IOException e)
            {
                throw new RuntimeException(
                        "Failed to read the number of lines of a new message due to an IOException from "
                                + remoteAddress, e);
            }
            catch (NumberFormatException e)
            {
                writeError("The number of lines in a message has to be a positive integer.");
                return;
            }
            if (numMsgs < 0)
            {
                writeError("the number of lines in a message must not be a negative number.");
                return;
            }
            String[] lines = new String[numLines];
            for (int j = 0; j < numLines; ++j)
            {
                try
                {
                    lines[j] = in.readLine();
                }
                catch (IOException e)
                {
                    throw new RuntimeException(
                            "Failed to read a line of a new message due to an IOException from "
                                    + remoteAddress, e);
                }
            }
            owner.addNewMessage(lines);
        }
    }

    /**
     * Handles the L [Count] instruction.
     */
    private void onListCommand(String instruction)
    {
        // default is to send all topics
        int numTopicLimit = Integer.MAX_VALUE;

        // validate and parse instruction arguments
        if (instruction.length() == 2)
        {
            if (instruction.charAt(1) != ' ')
            {
                writeError("invalid instruction \"" + instruction + "\"");
                return;
            }
        }
        else if (instruction.length() > 2)
        {
            String argument = instruction.substring(2);
            try
            {
                numTopicLimit = Integer.parseInt(argument);
            }
            catch (NumberFormatException e)
            {
                writeError("the argument for the list (L) command must be a positive integer.");
                return;
            }
            if (numTopicLimit < 0)
            {
                writeError("the argument for the list (L) command must not be negative.");
                return;
            }
        }

        List<Topic> topics = owner.getDb().getTopicsOrderedByTimestamp();

        // print the number of topics (one per line) we are going to send.
        out.println(Integer.min(topics.size(), numTopicLimit));

        // the stream pipeline will format the topics and print the resulting lines afterwords.
        Stream<Topic> topicStream = topics.stream();
        if (numTopicLimit != Integer.MAX_VALUE)
        {
            topicStream = topicStream.limit(numTopicLimit);
        }
        topicStream.map(topic -> "" + topic.lastPostTimestamp.getEpochSecond() + " " + topic.value)
                .forEachOrdered(line -> out.println(line));

        out.flush();
    }

    @Override
    public void close() throws IOException
    {
        connection.shutdownInput();
    }

    public void notifyTopicChanged(Topic topic)
    {
        topicChangeQueue.add(topic);
    }

    private void writeTopicUpdates()
    {
        ArrayList<Topic> updatedTopics = new ArrayList<>();
        for (Topic topic = topicChangeQueue.poll(); topic != null; topic = topicChangeQueue.poll())
        {
            updatedTopics.add(topic);
        }
        if (updatedTopics.size() > 0)
        {
            out.println("N " + updatedTopics.size());
            for (int i = 0; i < updatedTopics.size(); ++i)
            {
                Topic topic = updatedTopics.get(i);
                out.println("" + topic.lastPostTimestamp.getEpochSecond() + " " + topic.value);
            }
        }
        out.flush();
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
