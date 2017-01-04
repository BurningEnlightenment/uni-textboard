package onl.gassmann.textboard.server;

/**
 * Created by Henrik Gaßmann on 2016-12-16.
 */
public class ExecutionAbortedException extends RuntimeException
{
    public ExecutionAbortedException()
    {
        super();
    }

    public ExecutionAbortedException(String reason)
    {
        super(reason);
    }

    public ExecutionAbortedException(String reason, Throwable cause)
    {
        super(reason, cause);
    }
}
