package freenet.support.io;
import java.io.*;
import java.net.SocketException;

public class SocketExceptionOutputStream extends OutputStream {
    String msg;
    public SocketExceptionOutputStream(String msg) {this.msg = msg;}
    public void write(int b) throws IOException {
	throw new SocketException(msg);
    }
    public void write(byte[] buf, int off, int len) throws IOException {
	throw new SocketException(msg);
    }
}

