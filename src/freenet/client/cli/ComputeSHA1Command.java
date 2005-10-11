package freenet.client.cli;
import freenet.client.RequestProcess;
import freenet.client.ComputeSHA1Process;
import freenet.support.Bucket;
import freenet.config.Params;

public class ComputeSHA1Command implements ClientCommand {
    public String getName() {
        return "computeSHA1";
    }

    public String getUsage() {
        return "computeSHA1";
    }

    public String[] getDescription() {
        return new String[] {
            "Computes the SHA1 hash of the given data."
        };
    }

    public int argCount() {
        return 0;
    }

    public RequestProcess getProcess(Params p, Bucket metadata, Bucket data) {
        return new ComputeSHA1Process(data);
    }

    public boolean takesData() {
        return true;
    }

    public boolean givesData() {
        return false;
    }
}
