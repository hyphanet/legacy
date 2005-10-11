package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class FakeRandomAccessFilePool implements RandomAccessFilePool {
    volatile int totalOpenFiles;

    public int totalOpenFiles() {
        return totalOpenFiles;
    }

    public int maxOpenFiles() {
        return 0;
    }

    public PooledRandomAccessFile open(File f, String mode) throws IOException {
        return new MyPooledRandomAccessFile(f, mode);
    }

    class MyPooledRandomAccessFile extends RandomAccessFile implements PooledRandomAccessFile {

        public MyPooledRandomAccessFile(File f, String mode) throws IOException {
            super(f,mode);
            synchronized(FakeRandomAccessFilePool.this) {
                totalOpenFiles++;
            }
        }

        /**
         * Not in RandomAccessFile API, but because of synchronization
         * issues, it is better to put it here */
        public synchronized void sync() throws IOException {
            super.getFD().sync();
        }

        public void closeRAF() {}

        public synchronized void close() throws IOException {
            super.close();
            synchronized(FakeRandomAccessFilePool.this) {
                totalOpenFiles--;
            }
        }
    }
}
