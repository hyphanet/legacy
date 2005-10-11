package freenet.node.states.FCP;
import freenet.*;
import freenet.node.*;
import freenet.message.client.*;
import freenet.support.ArrayBucket;
import freenet.support.Logger;
import freenet.diagnostics.FieldSetFormat;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.IOException;
/**
 * Returns a message contings the contents of node Diagnostics module.
 *
 * @author oskar
 */

public class ReturnDiagnostics extends NewClientRequest {

    public ReturnDiagnostics(long id, PeerHandler source) {
        super(id, source);
    }

    public String getName() {
        return "Return Diagnostics Data";
    }

    public State receivedMessage(Node n, GetDiagnostics g) {
        try {
            ArrayBucket bucket = new ArrayBucket();
            PrintWriter bout = 
                new PrintWriter(bucket.getOutputStream());
            Core.diagnostics.writeVars(bout, new FieldSetFormat());
            bout.close();

            TrailerWriter tw = 
                source.sendMessage(new DiagnosticsReply(id, bucket.size()), 300*1000);
            if (tw != null) {
		OutputStream out = new TrailerWriterOutputStream(tw);
                byte[] b = new byte[Core.blockSize];
                int i;
                InputStream in = bucket.getInputStream();
                while ((i = in.read(b)) != -1) {
                    out.write(b, 0, i);
                }
                out.close();
            }
        } catch (SendFailedException sfe) {
            Core.logger.log(this, "Failed to return diagnostic data message",
                         sfe, Logger.MINOR);
        } catch (IOException e) {
            Core.logger.log(this, "IOException while sending diagnostic data",
                         e, Logger.MINOR);
        }
        return null;
    }
}
