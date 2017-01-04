package onl.gassmann.textboard.server.database;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Created by gassmann on 2017-01-03.
 */
public class Message
{
    public static final Comparator<Message> TIMESTAMP_COMPARATOR
            = Comparator.comparing(msg -> msg.timestamp, Comparator.reverseOrder());

    private static final Logger LOGGER = Logger.getLogger(Message.class.getName());

    public final Instant timestamp;
    final Path path;

    /**
     * constructs an invalid message which can be used to search in a list of messages for a specific timestamp with
     * standard java utils.
     */
    public Message(final Instant timestamp)
    {
        if (timestamp == null)
        {
            throw new NullPointerException("timestamp == null");
        }
        this.timestamp = timestamp;
        path = null;
    }

    /**
     * internal constructor which constructs metadata for an existing message from disk
     *
     * @param path the file path
     * @param expectedTopic the topic to which this message should belong
     */
    Message(Path path, String expectedTopic)
    {
        if (path == null)
        {
            throw new NullPointerException("path == null");
        }
        if (!Files.isRegularFile(path))
        {
            throw new IllegalArgumentException("The given path doesn't point to a regular file.");
        }

        String metaLine = null;
        try
        {
            BufferedReader reader = Files.newBufferedReader(path, UTF_8);
            metaLine = reader.readLine();
        }
        catch (IOException exc)
        {
            LOGGER.warning("Failed to read message files \"" + path + "\"; details:\n" + exc);
        }
        Instant tmp = null;
        if (metaLine != null)
        {
            final int delimiter = metaLine.indexOf(' ');
            if (delimiter > 0)
            {
                final String timestampString = metaLine.substring(0, delimiter);
                final String realTopic = metaLine.substring(delimiter + 1);

                // check whether this message resides in the correct directory - if not it won't be loaded
                if (expectedTopic.equals(realTopic))
                {
                    try
                    {
                        tmp = Instant.ofEpochSecond(Long.parseUnsignedLong(timestampString));
                    }
                    catch (RuntimeException exc)
                    {
                        LOGGER.warning("Failed to read message timestamp from \"" + path + "\"; details:\n" + exc);
                    }
                }
                else
                {
                    LOGGER.warning("The message at \"" + path + "\" is misplaced.");
                }
            }
            else
            {
                LOGGER.warning("The message \"" + path + "\" begins with a malformed meta line.");
            }
        }

        if (tmp != null)
        {
            this.timestamp = tmp;
            this.path = path;
        }
        else
        {
            this.timestamp = null;
            this.path = null;
        }
    }

    private Message(Path path, Instant timestamp)
    {
        this.path = path;
        this.timestamp = timestamp;
    }

    boolean isValid()
    {
        return timestamp != null && path != null && Files.isRegularFile(path);
    }

    public List<String> content()
    {
        try
        {
            return Files.readAllLines(path, UTF_8);
        }
        catch (IOException e)
        {
            LOGGER.warning("Failed to read the message \"" + path + "\".");
            return null;
        }
    }

    public List<String> format()
    {
        List<String> content = content();
        if (content != null)
        {
            content.add(0, Integer.toString(content.size()));
        }
        return content;
    }

    static Message create(final Path topicDbPath, final String[] lines)
    {
        if (lines == null)
        {
            throw new NullPointerException("lines == null");
        }
        if (lines.length < 1)
        {
            throw new IllegalArgumentException("A valid message has at least a meta line.");
        }

        // validate meta line first
        String metaLine = lines[0];
        final int separatorIndex = metaLine.indexOf(' ');
        if (separatorIndex < 1)
        {
            throw new IllegalArgumentException("Malformed meta line: either missing topic separator or timestamp.");
        }
        if (metaLine.length() == separatorIndex)
        {
            throw new IllegalArgumentException("Malformed meta line: no topic provided");
        }
        try
        {
            Long.parseUnsignedLong(metaLine.substring(0, separatorIndex));
        }
        catch (NumberFormatException exc)
        {
            throw new IllegalArgumentException("Malformed meta line: the timestamp ["
                                                       + metaLine.substring(0, separatorIndex)
                                                       + "] is not a valid number (or maybe too big)", exc);
        }

        String topic = metaLine.substring(separatorIndex + 1);
        Path topicPath = topicDbPath.resolve(Topic.encodeFilename(topic));
        try
        {
            Files.createDirectories(topicPath);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to create topic directory \"" + topicPath + "\"; details:\n" + e, e);
        }

        Path tmpPath;
        try
        {
            tmpPath = Files.createTempFile(null, null);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to create a file for the new message; details:\n" + e, e);
        }

        final Instant timestamp = Instant.now();
        // construct new meta line with the correct timestamp
        metaLine = "" + timestamp.getEpochSecond() + " " + topic;
        lines[0] = metaLine;
        try
        {
            Files.write(tmpPath, Arrays.asList(lines), UTF_8);
        }
        catch (IOException e)
        {
            try
            {
                Files.delete(tmpPath);
            }
            catch (Exception e2)
            {
                // there is nothing left we can do about the lingering file at this point
                LOGGER.warning("Failed to delete obsolete temporary file: " + tmpPath);
            }
            throw new RuntimeException("Failed to write the message content to the temporary file.", e);
        }

        // create a random file name within the topic dir (we abuse an universally unique identifier for this purpose)
        Path msgPath = topicPath.resolve(UUID.randomUUID()
                                                 .toString());
        try
        {
            // try to move the file atomically, so we ideally never have to deal with inconsistent database state
            Files.move(tmpPath, msgPath, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            LOGGER.warning("Failed to move the new message for \"" + topic + "\" with an atomic operation.");
            try
            {
                Files.move(tmpPath, msgPath);
            }
            catch (IOException e2)
            {
                throw new RuntimeException("Failed to move the message to the topic directory.", e2);
            }
            finally
            {
                try
                {
                    Files.delete(tmpPath);
                }
                catch (Exception e2)
                {
                    // there is nothing left we can do about the lingering file at this point
                    LOGGER.warning("Failed to delete obsolete temporary file: " + tmpPath);
                }
            }
        }
        catch (IOException e)
        {
            try
            {
                Files.delete(tmpPath);
            }
            catch (Exception e2)
            {
                // there is nothing left we can do about the lingering file at this point
                LOGGER.warning("Failed to delete obsolete temporary file: " + tmpPath);
            }
            throw new RuntimeException("Failed to move the message to the topic directory.", e);
        }

        return new Message(msgPath, timestamp);
    }
}
