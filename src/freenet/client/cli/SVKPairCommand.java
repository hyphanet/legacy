package freenet.client.cli;
import freenet.client.ComputeSVKPairProcess;
import freenet.client.RequestProcess;
import freenet.support.Bucket;
import freenet.config.Params;

public class SVKPairCommand implements ClientCommand {

    public String getName() {
        return "genkeys";
    }

    public String getUsage() {
        return getName();
    }

    public String[] getDescription() {
        return new String[] {
            "Generates a private / public key pair and prints the private key and the",
            "public key fingerprint to stdout"
        };
    }

    public int argCount() {
        return 0;
    }

    public RequestProcess getProcess(Params p, Bucket metadata, Bucket data) {
        return new ComputeSVKPairProcess(data);
    }

    public boolean takesData() {
        return false;
    }

    public boolean givesData() {
        return false;
    }
}
