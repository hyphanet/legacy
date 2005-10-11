package freenet.client.cli;
import freenet.client.InvertPrivateKeyProcess;
import freenet.client.RequestProcess;
import freenet.config.Params;
import freenet.support.Bucket;

/**
 * Command plugin for InvertPrivateKey (ie FCP InvertPrivateKey).
 *
 * @author giannij
 */
public class InvertPrivateKeyCommand implements ClientCommand {

    public InvertPrivateKeyCommand() {
    }

    public String getName() {
        return "invertkey";
    }

    public String getUsage() {
        return "invertkey <insert URI> | <raw private key>";
    }

    public String[] getDescription() {
        return new String[] {
            "Creates a public SSK key or URI from a private value.",
            "If <insert URI> is used, the public URI is returned.",
            "If <raw private key> is used, the public key for the",
            "(public,private) keypair is returned."
        };
    }

    public int argCount() {
        return 1;
    }

    public RequestProcess getProcess(Params p, Bucket mdata, Bucket data)
	throws CLIException {

        if (p.getNumArgs() < 2)
            throw new CLIException("invertprivatekey requires a private value as an argument");
        
        return new InvertPrivateKeyProcess(p.getArg(1));
    }

    public boolean takesData() {
        return false;
    }

    public boolean givesData() {
        return false;
    }
}



