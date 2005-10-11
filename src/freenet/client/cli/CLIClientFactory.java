package freenet.client.cli;
import freenet.client.ClientFactory;
import freenet.config.Config;
/**
 * A subinterface of ClientFactory for Interfaces that support 
 * freenet.client.CLI.
 *
 * To be usable, these must contain a Contructor from a Param of arguments
 * and a Logger, which may throw a CLIException.
 *
 * @author oskar
 */
public interface CLIClientFactory extends ClientFactory {

    /**
     * Return the name and a short description of the client factory.
     * @return Something like "FNPClient, Fred 0.4, protocol 1.45."
     */
    String getDescription();

    /**
     * Returns the config options accepted by this client.
     * @return  the supported options.
     */
    Config getOptions();

    /**
     * Stops this client factory.
     */
    void stop();

}
