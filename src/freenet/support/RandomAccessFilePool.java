package freenet.support;

import java.io.*;

public interface RandomAccessFilePool {
    public int totalOpenFiles();
    public int maxOpenFiles();
    public PooledRandomAccessFile open(File f, String mode) throws IOException;
}
