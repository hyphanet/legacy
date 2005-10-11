package freenet.fs.tests;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import junit.framework.TestCase;
import freenet.fs.dir.Directory;
import freenet.fs.dir.LossyDirectory;
import freenet.fs.dir.NativeFSDirectory;
import freenet.support.test.SimpleTestRunner;

public class DirectoryTest extends TestCase {

    public static void main(String[] args) {
        SimpleTestRunner.main(new String[] {DirectoryTest.class.getName()});
    }

    Directory dir;
    LossyDirectory dsDir;

    public DirectoryTest(String name) {
        super(name);
    }

    public void setUp() {
        try {
        	dir = new NativeFSDirectory(new File("testdir"), 10000, 4096, false, 0.25F, 256);
            dsDir = new LossyDirectory(0x0001, dir);
        } catch (IOException e){
            fail(e.toString());
        }
    }

    public static class WriteThread extends Thread {

        boolean done = false;
        Throwable t = null;

        OutputStream out;
        boolean delay;

        public WriteThread(OutputStream out, boolean delay) {
            this.out = out;
            this.delay = delay;
        }

        public void run() {
            try {
                for (int i = 1 ; i <= 499; i++) {
                    out.write(i & 0xff);
                    if (delay)
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException ie) {}
                }
            } catch (Throwable t) {
                this.t = t;
            } finally {
                synchronized(this) {
                    done = true;
                    notifyAll();
                }

            }
        }

    }




}
