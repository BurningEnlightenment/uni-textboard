package onl.gassmann.textboard.server.database;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.xml.bind.DatatypeConverter.parseHexBinary;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * Created by gassmann on 2017-01-03.
 */
public class Topic
{
    private static final Logger LOGGER = Logger.getLogger(Topic.class.getName());
    private static final ThreadLocal<CharsetDecoder> UTF_8_DECODER = ThreadLocal.withInitial(() -> UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT));

    public static final Comparator<Topic> TIMESTAMP_COMPARATOR = (a, b) -> b.lastPostTimestamp.compareTo(a.lastPostTimestamp);

    public final String value;
    public final Path path;
    public final Instant lastPostTimestamp;

    private final List<Message> messages;

    Topic(Path path)
    {
        // derive topic string from path
        value = decodeFilename(path.getFileName().toString());

        if (value != null)
        {
            this.path = path;

            Stream<Path> msgPathStream;
            try
            {
                msgPathStream = Files.list(path);
            }
            catch (IOException e)
            {
                LOGGER.warning("Failed to list files of topic \"" + path + "\"; details:\n" + e);
                msgPathStream = null;
            }
            if (msgPathStream != null)
            {
                // read messages
                messages = Collections.unmodifiableList(
                        msgPathStream.filter(msgPath -> Files.isRegularFile(msgPath))
                                .map(msgPath -> new Message(msgPath, value))
                                .filter(Message::isValid)
                                .sorted(Message.TIMESTAMP_COMPARATOR)
                                .collect(Collectors.toList()));

                lastPostTimestamp = messages.isEmpty() ? null : messages.get(0).timestamp;
            }
            else
            {
                messages = null;
                lastPostTimestamp = null;
            }
        }
        else
        {
            this.path = null;
            messages = null;
            lastPostTimestamp = null;
        }
    }

    /*
    Topic(Path topicDbPath, String topic)
    {
        if (topicDbPath == null)
        {
            throw new NullPointerException("topicDbPath == null");
        }
        if (topic == null)
        {
            throw new NullPointerException("topic == null");
        }
        Path topicPath = topicDbPath.resolve(encodeFilename(topic));
        if (!Files.isDirectory(topicPath))
        {
            throw new IllegalArgumentException("The topic directory \"" + topicPath +"\" doesn't exist.");
        }
    }
    */

    boolean isValid()
    {
        return value != null && path != null && messages != null && !messages.isEmpty();
    }

    public List<Message> getMessages()
    {
        return messages;
    }

    private static String decodeFilename(String filename)
    {
        final byte[] decoded;
        try
        {
            decoded = parseHexBinary(filename);
        }
        catch (IllegalArgumentException exc)
        {
            LOGGER.warning("Failed to decode the base16 topic name \"" + filename + "\"");
            return null;
        }
        try
        {
            return UTF_8_DECODER.get()
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
        }
        catch (CharacterCodingException exc)
        {
            LOGGER.warning("The encoded topic name \"" + filename + "\" translates to an invalid string.");
            return null;
        }
    }

    static String encodeFilename(String topic)
    {
        return printHexBinary(topic.getBytes(UTF_8));
    }
}
