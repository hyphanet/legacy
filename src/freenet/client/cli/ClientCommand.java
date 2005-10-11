package freenet.client.cli;
import freenet.config.Params;
import freenet.support.Bucket;
import freenet.client.RequestProcess;

public interface ClientCommand {

    /**
     * Return the name of the command.
     */
    public String getName();

    /**
     * Returns a usage string, like "get <uri>". Only arguments read by
     * this Command should be included (ie, not filename).
     */
    public String getUsage();

    /**
     * Returns full description of this command, seperated into <78
     * character lines.
     */
    public String[] getDescription();

    /**
     * Returns the number of essential arguments this command takes.
     */
    public int argCount();

    /**
     * Return a request process.
     */
    public RequestProcess getProcess(Params p, Bucket metadata, 
                                     Bucket data) throws CLIException ;

    /**
     * Whether this operation requires data in the buckets at start.
     */
    public boolean takesData();

    /**
     * Whether this operation leaves new data in the buckets at the end.
     */
    public boolean givesData();


}
