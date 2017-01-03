package onl.gassmann.textboard.server.database;

/**
 * Represents an immutable Timestamp as defined by the TextBoard Protocol.
 * Created by gassmann on 2017-01-03.
 */
public class Timestamp implements Comparable<Timestamp>
{
    /**
     * A global constant which may be used as default value
     */
    public static final Timestamp ZERO = new Timestamp(0);

    /**
     * The number of seconds since the UNIX epoch defining the Timestamp.
     */
    public final long value;

    public Timestamp(long value)
    {
        this.value = value;
    }

    /**
     * Compares this Timestamp to another instance.
     * Defines a total descending order i.e. newer Messages will compare as less than older messages.
     * @param o the other Timestamp
     */
    @Override
    public int compareTo(Timestamp o)
    {
        return o == null ? -1 : Long.compare(o.value, value);
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(value);
    }

    @Override
    public boolean equals(Object obj)
    {
        return this == obj || obj instanceof Timestamp && value == ((Timestamp)obj).value;
    }
}
