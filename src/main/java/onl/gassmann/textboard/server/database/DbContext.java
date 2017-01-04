package onl.gassmann.textboard.server.database;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by gassmann on 2017-01-03.
 */
public class DbContext
{
    private static final Pattern B16_PATTERN = Pattern.compile("\\A[0-9a-fA-F]{2,}\\z");

    private final Path topicDbPath;

    private final ConcurrentMap<String, Topic> topics;
    private volatile List<Topic> topicsByTimestamp;
    private volatile List<Message> messagesByTimestamp;

    public DbContext(Path dbPath)
    {
        // validate the database path
        if (dbPath == null)
        {
            throw new NullPointerException("dbPath == null");
        }
        if (!Files.exists(dbPath))
        {
            try
            {
                Files.createDirectories(dbPath);
            }
            catch (IOException exc)
            {
                // todo throw proper exception
                throw new RuntimeException("Failed to create the database directory.", exc);
            }
        }
        else if (!Files.isDirectory(dbPath))
        {
            // todo throw proper exception
            throw new RuntimeException("The given database path doesn't point to a directory.");
        }

        // read the value database
        topicDbPath = dbPath.resolve("topic");
        if (!Files.exists(topicDbPath))
        {
            try
            {
                Files.createDirectory(topicDbPath);
            }
            catch (IOException exc)
            {
                // todo throw proper exception
                throw new RuntimeException("Failed to create the value directory within the database directory.", exc);
            }

            topics = new ConcurrentHashMap<>();
            topicsByTimestamp = new LinkedList<>();
            messagesByTimestamp = new ArrayList<>();
        }
        else if (!Files.isDirectory(topicDbPath))
        {
            // todo throw proper exception
            throw new RuntimeException(
                    "Within the database directory there exists a \"value\" entity which isn't a directory.");
        }
        else
        {
            Stream<Path> topicPaths;
            try
            {
                topicPaths = Files.list(topicDbPath);
            }
            catch (IOException exc)
            {
                throw new RuntimeException("Failed to list the topics within the value database directory.", exc);
            }

            final Predicate<String> b64Predicate = B16_PATTERN.asPredicate();

            this.topicsByTimestamp = Collections.unmodifiableList(
                    topicPaths.filter(path -> b64Predicate.test(path.getFileName()
                                                                          .toString()) && Files.isDirectory(path))
                    .map(Topic::new)
                    .filter(Topic::isValid)
                    .sorted(Topic.TIMESTAMP_COMPARATOR)
                    .collect(Collectors.toList()));

            this.topics = topicsByTimestamp.stream()
                    .collect(Collectors.toConcurrentMap(topic -> topic.value, Function.identity(), (l, r) -> l,
                                                        ConcurrentHashMap::new
                    ));

            this.messagesByTimestamp = Collections.unmodifiableList(
                    (ArrayList<Message>)topicsByTimestamp.stream()
                    .map(Topic::getMessages)
                    .flatMap(List::stream)
                    .sorted(Message.TIMESTAMP_COMPARATOR)
                    .collect(Collectors.toCollection(ArrayList::new)));
        }
    }

    public Topic getTopic(String topic)
    {
        return topics.get(topic);
    }

    public List<Topic> getTopicsOrderedByTimestamp()
    {
        return topicsByTimestamp;
    }

    public List<Message> getMessagesOrderedByTimestamp()
    {
        return messagesByTimestamp;
    }

    public Topic put(String[] msg)
    {
        Message compiled = Message.create(topicDbPath, msg);
        return updateIndex(compiled);
    }

    private synchronized Topic updateIndex(Message msg)
    {
        Path topicPath = msg.path.getParent();
        Topic updated = new Topic(topicPath);
        if (!updated.isValid())
        {
            throw new RuntimeException("Temporary failure: could not create topic index with the new message.");
        }

        ArrayList<Message> msgIndex = new ArrayList<>(messagesByTimestamp.size() + 1);
        {
            ListIterator<Message> it = messagesByTimestamp.listIterator();
            while (it.hasNext())
            {
                Message nxt = it.next();
                if (nxt.timestamp.compareTo(msg.timestamp) < 1)
                {
                    break;
                }
            }
            if (it.hasPrevious())
            {
                int insertionIndex = it.previousIndex();
                msgIndex.addAll(messagesByTimestamp.subList(0,insertionIndex));
                msgIndex.add(msg);
                msgIndex.addAll(messagesByTimestamp.subList(insertionIndex, messagesByTimestamp.size()));
            }
            else
            {
                msgIndex.add(msg);
                msgIndex.addAll(messagesByTimestamp);
            }
        }

        LinkedList<Topic> topicIndex = topicsByTimestamp.stream()
                .filter(topic -> !topic.value.equals(updated.value))
                .collect(Collectors.toCollection(LinkedList::new));
        {
            ListIterator<Topic> it = topicIndex.listIterator();
            while (it.hasNext())
            {
                Topic nxt = it.next();
                if (nxt.lastPostTimestamp.compareTo(updated.lastPostTimestamp) < 1)
                {
                    break;
                }
            }
            if (it.hasPrevious())
            {
                it.previous();
            }
            it.add(updated);
        }

        messagesByTimestamp = msgIndex;
        topicsByTimestamp = topicIndex;
        topics.put(updated.value, updated);

        return updated;
    }
}
