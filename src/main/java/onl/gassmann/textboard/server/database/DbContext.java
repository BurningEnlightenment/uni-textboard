package onl.gassmann.textboard.server.database;

import java.io.IOException;
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
 * Provides access to message and topic meta data.
 * The meta data is provided as different views which can be accessed through the various getter methods getTopic(),
 * getTopicsOrderedByTimestamp() and getMessagesOrderedByTimestamp(). The returned views represent the database state
 * at the time of access and are completely stable and consist only of immutable objects, i.e. database updates will not
 * affect an already retrieved view in _any_ way. This has two very important consequences: Iterating over the returned
 * data is (thread) safe and there is no need to synchronize meta data access in any way.
 * Database updates happen in a copy on write fashion, i.e. all updates are performed on copies of the current view and
 * the old views are than exchanged with the updated copies. However updating the database index is obviously _not_
 * thread safe and thus happens in the synchronized updateIndex() method, so all database index updates happen in
 * sequential order.
 * As a consequence of these properties the retrieved views are not guaranteed to be consistent among each other, because
 * between the retrieval of two different views a new message might have been added which is thus missing from the
 * earlier one. Luckily for us the protocol doesn't require this property (at least in the way I have implemented it).
 *
 * Each message is stored in its own file with a random UUID as filename. The message files are grouped in directories
 * which use the HEX encoded UTF-8 representation of the topic as their name (please note that I haven't applied any
 * sort of unicode normalization, so you are able to create two _different_ topics which completely _look_ the same
 * see https://en.wikipedia.org/wiki/Unicode_equivalence). I beg your pardon for abusing the filesystem as database ;).
 *
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
            // the database directory doesn't exist so we try to create it.
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
            // the topic database directory doesn't exist so we try to create it.
            try
            {
                Files.createDirectory(topicDbPath);
            }
            catch (IOException exc)
            {
                // todo throw proper exception
                throw new RuntimeException("Failed to create the value directory within the database directory.", exc);
            }

            // no db directory -> no data to be read
            topics = new ConcurrentHashMap<>();
            topicsByTimestamp = new LinkedList<>();
            messagesByTimestamp = new ArrayList<>();
        }
        else if (!Files.isDirectory(topicDbPath))
        {
            throw new RuntimeException(
                    "Within the database directory there exists a \"value\" entity which isn't a directory.");
        }
        else
        {
            // parse the topic directories within the topic db directory
            Stream<Path> topicPaths;
            try
            {
                topicPaths = Files.list(topicDbPath);
            }
            catch (IOException exc)
            {
                throw new RuntimeException("Failed to list the topics within the value database directory.", exc);
            }

            final Predicate<String> b16Predicate = B16_PATTERN.asPredicate();

            // this fail safe stream operation iterates over all topic directories and constructs a topic object for
            // each of them which in turn parses and validates all message files within itself. All invalid objects
            // (messages and topics) which are produced in the process are ignored.
            // Afterwords we order the topics by their timestamp (see Topic.TIMESTAMP_COMPARATOR)
            this.topicsByTimestamp = Collections.unmodifiableList(
                    topicPaths.filter(path -> b16Predicate.test(path.getFileName()
                                                                        .toString()) && Files.isDirectory(path))
                            .map(Topic::new)
                            .filter(Topic::isValid)
                            .sorted(Topic.TIMESTAMP_COMPARATOR)
                            .collect(Collectors.toList()));

            // create the topic string -> topic mapping
            this.topics = topicsByTimestamp.stream()
                    .collect(Collectors.toConcurrentMap(topic -> topic.value, Function.identity(), (l, r) -> l,
                                                        ConcurrentHashMap::new
                    ));

            // aggregate and sort all messages by their timestamp (see Message.TIMESTAMP_COMPARATOR)
            //noinspection RedundantCast
            this.messagesByTimestamp = Collections.unmodifiableList(
                    (ArrayList<Message>) topicsByTimestamp.stream()
                            .map(Topic::getMessages)
                            .flatMap(List::stream)
                            .sorted(Message.TIMESTAMP_COMPARATOR)
                            .collect(Collectors.toCollection(ArrayList::new)));
        }
    }

    /**
     * @return a topic's view on all it's message.
     */
    public Topic getTopic(String topic)
    {
        return topics.get(topic);
    }

    /**
     * @return all topics ordered by their timestamp (see Topic.TIMESTAMP_COMPARATOR)
     */
    public List<Topic> getTopicsOrderedByTimestamp()
    {
        return topicsByTimestamp;
    }

    /**
     * @return all messages ordered by their timestamp (see Message.TIMESTAMP_COMPARATOR)
     */
    public List<Message> getMessagesOrderedByTimestamp()
    {
        return messagesByTimestamp;
    }

    /**
     * Inserts a new message into the database (writes the contents to file and updates the index)
     * @param msg the raw message to be added with the number of lines line removed.
     * @return the topic the message has been added to
     */
    public Topic put(String[] msg)
    {
        // the message creation/persistence can safely be done in parallel
        Message compiled = Message.create(topicDbPath, msg);
        return updateIndex(compiled);
    }

    /**
     * Adds the given *new* message to the index. The behaviour is undefined if the message has already been added.
     */
    private synchronized Topic updateIndex(Message msg)
    {
        Topic updated;
        {
            // if an appropriate topic object already exists we will "update" (cow style) in order to avoid message
            // duplication
            Topic old = topics.get(msg.topicValue);
            updated = old == null
                      ? new Topic(msg.path.getParent())
                      : old.add(msg);
        }
        if (!updated.isValid())
        {
            throw new RuntimeException("Temporary failure: could not create topic index with the new message.");
        }

        // copy the old message index and insert the new element
        ArrayList<Message> msgIndex = new ArrayList<>(messagesByTimestamp.size() + 1);
        {
            ListIterator<Message> it = messagesByTimestamp.listIterator();
            // look for the appropriate insertion point in order to maintain the sorted property
            // we use a linear time search algorithm because new messages will always be inserted somewhere near the
            // beginning of the list.
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
                // copy elements before insertion index than insert the new message than add the rest
                int insertionIndex = it.previousIndex();
                msgIndex.addAll(messagesByTimestamp.subList(0, insertionIndex));
                msgIndex.add(msg);
                msgIndex.addAll(messagesByTimestamp.subList(insertionIndex, messagesByTimestamp.size()));
            }
            else
            {
                // insert at the beginning than copy over the rest
                msgIndex.add(msg);
                msgIndex.addAll(messagesByTimestamp);
            }
        }

        // copy the topic index and filter any old topic objects out during the process
        LinkedList<Topic> topicIndex = topicsByTimestamp.stream()
                .filter(topic -> !topic.value.equals(updated.value))
                .collect(Collectors.toCollection(LinkedList::new));
        {
            ListIterator<Topic> it = topicIndex.listIterator();
            // look for the insertion point
            while (it.hasNext())
            {
                Topic nxt = it.next();
                if (nxt.lastPostTimestamp.compareTo(updated.lastPostTimestamp) < 1)
                {
                    break;
                }
            }
            // !it.hasPrevious() -> insert at the beginning
            if (it.hasPrevious())
            {
                // we iterated one past the insertion point (by iterator design)
                it.previous();
            }
            it.add(updated);
        }

        // now exchange all old views against the updated ones
        // we do this down here in order to keep the views merely consistent among each other
        // (even though it doesn't make any difference regarding properties/guarantees)
        messagesByTimestamp = msgIndex;
        topicsByTimestamp = topicIndex;
        topics.put(updated.value, updated);

        // return the updated topic object
        return updated;
    }
}
