package onl.gassmann.textboard.server;

import onl.gassmann.textboard.server.database.Message;
import onl.gassmann.textboard.server.database.Topic;

import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by gassmann on 2017-01-03.
 */
class ClientConnection implements Runnable,
        Closeable
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
            throw new RuntimeException("Failed to create a buffered reader for connection " + remoteAddress + ".", exc);
        }
    }

    @Override
    public void run()
    {
        try
        {
            // connection main loop
            // we loop until something very bad happens (e.g. connection reset) or the client gracefully disconnects
            while (!connection.isInputShutdown())
            {
                final String instruction;
                try
                {
                    instruction = in.readLine();
                }
                catch (IOException exc)
                {
                    LOGGER.warning("Failed to read the next instruction from " + remoteAddress + "." +
                                           " Probably the client was killed; details:\n" + exc);
                    break;
                }
                if (instruction.length() < 1)
                {
                    writeError("Got an empty line instead of an instruction.");
                    continue;
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

                    // get messages by topic
                    case 'T':
                        onTopicCommand(instruction);
                        break;

                    // get all message newer than the specified time point
                    case 'W':
                        onNewsCommand(instruction);
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
            // always close the connection in order to avoid native resource leaks
            try
            {
                connection.close();
            }
            catch (IOException exc)
            {
                LOGGER.severe(
                        "Failed to close the socket for " + getRemoteAddress() + "; details:\n" + exc.getMessage());
            }
            finally
            {
                // notify the server that this connection is no longer active
                owner.notifyConnectionClosed(this);
            }
        }
    }

    /**
     * Handles the W [timestamp] command,
     */
    private void onNewsCommand(final String instruction)
    {
        // validate instruction
        final int instructionLength = instruction.length();
        if (instructionLength == 1 || instructionLength == 2 && instruction.charAt(1) == ' ')
        {
            writeError("the W command is missing it's argument.");
            return;
        }
        else if (instructionLength == 2)
        {
            writeError("invalid command \"" + instruction + "\".");
            return;
        }

        final Instant since;
        try
        {
            long rawSince = Long.parseLong(instruction.substring(2));
            since = Instant.ofEpochSecond(rawSince);
        }
        catch (NumberFormatException e)
        {
            writeError("the argument for W must be a valid integer.");
            return;
        }
        catch (DateTimeException e)
        {
            writeError("the argument must be in the range of valid time points.");
            return;
        }

        Message constraint = new Message(since);

        List<Message> msgList = owner.getDb()
                .getMessagesOrderedByTimestamp();

        // determine which messages need to be send to the client.
        int listSize = msgList.size();
        int limit = Collections.binarySearch(msgList, constraint, Message.TIMESTAMP_COMPARATOR);
        if (limit < 0)
        {
            // no messages which exactly match the timestamp (see binarySearch documentation)
            limit = -limit - 1;
        }
        else
        {
            // we have one or more messages which have exactly the requested timestamp which we *all* include in our
            // response. Ich habe das "seit" in der Aufgabenstellung als inklusiv interpretiert, wäre ein exklusives
            // limit gefordert müsste man einfach an dieser Stelle dekrementieren.
            do
            {
                limit += 1;
            }
            while (limit < listSize
                    && Message.TIMESTAMP_COMPARATOR.compare(constraint, msgList.get(limit)) == 0);
        }

        // write all messages to the client
        out.println(limit);
        msgList.stream()
                .limit(limit)
                .map(Message::format)
                .flatMap(List::stream)
                .forEachOrdered(out::println);
        out.flush();
    }

    /**
     * Handles the T [topic] command.
     */
    private void onTopicCommand(final String instruction)
    {
        // validate instruction
        final int instructionLength = instruction.length();
        if (instructionLength == 1 || instructionLength == 2 && instruction.charAt(1) == ' ')
        {
            writeError("the topic (T) command is missing it's argument.");
            return;
        }
        else if (instructionLength == 2)
        {
            writeError("invalid command \"" + instruction + "\".");
            return;
        }

        // retrieve topic entry from database
        String topicId = instruction.substring(2);
        Topic topic = owner.getDb()
                .getTopic(topicId);
        if (topic == null)
        {
            // no messages regarding this topic -> we send an empty message list because it wasn't specified whether
            // this should be an error or not.
            out.println("0");
            return;
        }

        // read all messages of the requested topic into memory and discard those entries where the read operation failed
        List<List<String>> contentList = topic.getMessages()
                .stream()
                .map(Message::format)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // now we simply print the number of messages and the already formatted messages
        out.println(contentList.size());
        contentList.stream()
                .flatMap(List::stream)
                .forEachOrdered(out::println);
        out.flush();
    }

    /**
     * Handles the X command.
     * @return true if invocation was successful.
     */
    private boolean onExitCommand(final String instruction)
    {
        // validate instruction
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

    /**
     * Handles the P command.
     */
    private void onPutCommand(final String instruction)
    {
        // validate instruction
        if (instruction.length() != 1)
        {
            // P followed by an argument
            writeError("the put instruction (P) must not contain any arguments.");
            return;
        }

        // the next line must be the number of messages to expect
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
            writeError("The number of messages must not be a negative number.");
            return;
        }

        // receive messages
        for (int i = 0; i < numMsgs; ++i)
        {
            // how many lines does the message consist of?
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

            // read as many lines as specified from the connection
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
            // if everything went well we add the new message to our TextBoard
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

        List<Topic> topics = owner.getDb()
                .getTopicsOrderedByTimestamp();

        // print the number of topics (one per line) we are going to send.
        out.println(Integer.min(topics.size(), numTopicLimit));

        // the stream pipeline will format the topics and print the resulting lines afterwords.
        Stream<Topic> topicStream = topics.stream();
        if (numTopicLimit != Integer.MAX_VALUE)
        {
            topicStream = topicStream.limit(numTopicLimit);
        }
        topicStream.map(topic -> "" + topic.lastPostTimestamp.getEpochSecond() + " " + topic.value)
                .forEachOrdered(out::println);

        out.flush();
    }

    /**
     * Closes this connection.
     * @throws IOException
     */
    @Override
    public void close() throws IOException
    {
        connection.shutdownInput();
    }

    /**
     * Feeds a changed topic which will be appended after the next response.
     * @param topic
     */
    public void notifyTopicChanged(Topic topic)
    {
        if (topic != null)
        {
            topicChangeQueue.add(topic);
        }
    }

    /**
     * Writes all changed topics to the client.
     */
    private void writeTopicUpdates()
    {
        // poll the thread safe queue until empty
        HashMap<String, Topic> updatedTopics = new HashMap<>();
        for (Topic topic = topicChangeQueue.poll(); topic != null; topic = topicChangeQueue.poll())
        {
            // deduplicate entries in the queue, if a topic was updated twice we only include the later updated entry
            Topic dupe = updatedTopics.get(topic.value);
            if (dupe == null || dupe != topic && dupe.lastPostTimestamp.compareTo(topic.lastPostTimestamp) == -1)
            {
                updatedTopics.put(topic.value, topic);
            }
        }

        if (!updatedTopics.isEmpty())
        {
            // we write the number of topics and afterwards a line per topic in the order of their timestamp (newest first)
            out.println("N " + updatedTopics.size());
            updatedTopics.entrySet()
                    .stream()
                    .map(pair -> pair.getValue())
                    .sorted(Topic.TIMESTAMP_COMPARATOR)
                    .map(topic -> "" + topic.lastPostTimestamp.getEpochSecond() + " " + topic.value)
                    .forEachOrdered(out::println);
        }

        // generally flush the output buffer
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

    /**
     * Helper function for error cases.
     */
    private void writeError(String msg)
    {
        out.println("E " + msg);
        out.flush();
    }
}
