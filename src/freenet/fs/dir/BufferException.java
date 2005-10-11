package freenet.fs.dir;

import java.io.IOException;

public class BufferException extends IOException {

    BufferException() {
        super();
    }
    
    BufferException(String s) {
        super(s);
    }
}
