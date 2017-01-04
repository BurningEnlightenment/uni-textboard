package onl.gassmann.textboard.server.database;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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

    public static final Comparator<Topic> TIMESTAMP_COMPARATOR
            = Comparator.comparing(topic -> topic.lastPostTimestamp, Comparator.reverseOrder());

    public final String value;
    public final Path path;
    public final Instant lastPostTimestamp;

    private final List<Message> messages;

    Topic(Path path)
    {
        // derive topic string from path
        value = decodeFilename(path.getFileName()
                                       .toString());

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
                        (ArrayList<Message>) msgPathStream.filter(msgPath -> Files.isRegularFile(msgPath))
                                .map(msgPath -> new Message(msgPath, value))
                                .filter(Message::isValid)
                                .sorted(Message.TIMESTAMP_COMPARATOR)
                                .collect(Collectors.toCollection(ArrayList::new)));

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

    private Topic(Topic old, Message msg)
    {
        if (old == null)
        {
            throw new NullPointerException("old == null");
        }
        if (msg == null)
        {
            throw new NullPointerException("msg == null");
        }
        if (!old.isValid())
        {
            throw new IllegalArgumentException("The old topic isn't valid.");
        }
        if (!msg.isValid())
        {
            throw new IllegalArgumentException("The new message isn't valid");
        }
        if (!msg.path.startsWith(old.path))
        {
            throw new IllegalArgumentException("The new message isn't in the topic directory.");
        }

        value = old.value;
        path = old.path;

        ArrayList<Message> msgList = new ArrayList<>(old.messages.size() + 1);
        msgList.add(msg);
        msgList.addAll(old.messages);
        Collections.sort(msgList, Message.TIMESTAMP_COMPARATOR);

        messages = Collections.unmodifiableList(msgList);
        lastPostTimestamp = messages.get(0).timestamp;
    }

    Topic add(Message msg)
    {
        return new Topic(this, msg);
    }

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
