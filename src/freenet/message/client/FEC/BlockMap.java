package freenet.message.client.FEC;

import java.io.IOException;

import freenet.BaseConnectionHandler;
import freenet.FieldSet;
import freenet.RawMessage;
import freenet.message.client.ClientMessage;
import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.io.ReadInputStream;

/**
 * Message used to define the CHKs of the chunks of a Splitfile.
 */
public class BlockMap extends ClientMessage {

    public static final String messageName = "BlockMap";

    private String[] dataBlocks = null;
    private String[] checkBlocks = null;

    // From wire
    public BlockMap(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw);
        formatError = readFrom(otherFields);
    }

    // From a ReadInputStream.
    public BlockMap(long id, ReadInputStream in) throws IOException {
        super(id, new FieldSet()); 
        String msgName = in.readln();
        if (!msgName.equals("BlockMap")) {
            throw new IOException("Input stream doesn't contain a BlockMap msg!");
        }
        FieldSet fs = new FieldSet(in);
        formatError = readFrom(fs);
        close = false;
    }

    // To wire.
    public BlockMap(long id, String[] dataBlocks, String[] checkBlocks) {
        super(id, new FieldSet()); 

        this.dataBlocks = dataBlocks;
        FieldSet blocks = new FieldSet();
        int i = 0;
        for (i = 0; i < dataBlocks.length; i++) {
            blocks.put(Integer.toHexString(i), dataBlocks[i]);
        }
        otherFields.put("Block", blocks);

        this.checkBlocks = checkBlocks;
        FieldSet checks = new FieldSet();
        for (i = 0; i < checkBlocks.length; i++) {
            checks.put(Integer.toHexString(i), checkBlocks[i]);
        }
        otherFields.put("Check", checks);
    }

    protected  boolean readFrom(FieldSet fs) {
        boolean ret = false;
        try {
            int index = 0;
            DoublyLinkedList list = new DoublyLinkedListImpl();
            // Read data blocks
                FieldSet blocks = fs.getSet("Block");
			if (blocks != null) {
				String blockValue = blocks.getString(Integer.toString(0, 16));
                while (blockValue != null) {
                    list.push(new StringItem(blockValue));
                    index++;
					blockValue = blocks.getString(Integer.toString(index, 16));
                }
                if (index == 0) {
                    return false;
                }
                dataBlocks = new String[index];
                index = 0;
                DoublyLinkedList.Item cursor = list.head();
                while (cursor != null) {
                    dataBlocks[index++] = ((StringItem)cursor).value;
                    cursor = list.next(cursor);
                }
                list.clear();
			} else {
                return false;
            }

            // Read check blocks
			blocks = fs.getSet("Check");
			if (blocks != null) {
                index = 0;
				String checkValue =
					blocks.getString(Integer.toString(index, 16));
                while (checkValue != null) {
                    list.push(new StringItem(checkValue));
                    index++;
					checkValue = blocks.getString(Integer.toString(index, 16));
                }
                if (index == 0) {
                    return false;
                }
                checkBlocks = new String[index];
                index = 0;
                DoublyLinkedList.Item cursor = list.head();
                while (cursor != null) {
                    checkBlocks[index++] = ((StringItem)cursor).value;
                    cursor = list.next(cursor);
                }
                list.clear();
			} else {
                // Need to handle this case for non-redundant
                // SplitFiles.
                checkBlocks = new String[0];
            }
            ret = true;

		} catch (Exception e) {
            e.printStackTrace(); // REDFLAG: remove.
        }
        return ret;
    }

    public String getMessageName() {
        return messageName;
    }
	public final void setClose(boolean value) {
		close = value;
	}

	public final String[] getDataBlocks() {
		return dataBlocks;
	}
	public final String[] getCheckBlocks() {
		return checkBlocks;
	}

    public void dump() {
		System.err.println(
			"------------------------------------------------------------");
        System.err.println("BlockMap dump");
        int i = 0;
        if (dataBlocks != null) {
            for (i = 0; i < dataBlocks.length; i++) {
                System.err.println("   block[" + i + "] : " + dataBlocks[i]); 
            }
        }
        if (checkBlocks != null) {
            for (i = 0; i < checkBlocks.length; i++) {
                System.err.println("   check[" + i + "] : " + checkBlocks[i]); 
            }
        }

		System.err.println(
			"------------------------------------------------------------");
        
    } 
    private static class StringItem extends DoublyLinkedListImpl.Item {
        private String value = null;
        StringItem(String value) {
            this.value = value;
        }
    }
}
