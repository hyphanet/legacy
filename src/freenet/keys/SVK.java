package freenet.keys;

import java.io.InputStream;

import freenet.Key;
import freenet.KeyException;
import freenet.Presentation;
import freenet.Storables;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Digest;
import freenet.crypt.SHA1;
import freenet.crypt.Util;
import freenet.support.io.DataNotValidIOException;
import freenet.support.io.VerifyingInputStream;

/**
 * SVK stands for Signature Verifiable Key. This key is composed using a DSS
 * public key, and is thus verifyable by any node in the network.
 * 
 * KHKs are keyType 02 01. ?
 * 
 * @author Scott G. Miller
 */
public final class SVK extends Key {

	public static final int SVK_MAXSIZE = 32768, SVK_LOG2_MAXSIZE = 15;

	// FIXME -- change key number -- is 0x0203 ok?
	public static int keyNumber = 0x0203;

	private byte[] root;

	/**
	 * Create a new SVK from the given byte representation.
	 */
	public SVK(byte[] keyval) throws KeyException {
		super(keyval);
		if (val.length != 23
			|| (val[21] & 0xff) != (keyNumber >> 8 & 0xff)
			|| (val[22] & 0xff) != (keyNumber & 0xff))
			throw new KeyException("Byte array does not contain a SVK");
		setRootValue(val);
	}

	/**
	 * Create a new SVK from the given byte representation, then update the key
	 * value using the document name.
	 */
	public SVK(byte[] root, String docname) throws KeyException {
		this(root = copyval(root));
		if (docname != null) {
			Digest ctx = SHA1.getInstance();
			ctx.update(val, 0, 20);
			ctx.update(Util.hashString(SHA1.getInstance(), docname));
			System.arraycopy(ctx.digest(), 0, val, 0, 20);
		}
	}

	public SVK(DSAPublicKey pk, String docname, int log2size) {
		super(20, log2size, keyNumber);

		Digest ctx = SHA1.getInstance();
		ctx.update(pk.asBytes());
		System.arraycopy(ctx.digest(), 0, val, 0, 20);
		setRootValue(val);

		if (docname != null) {
			ctx.update(val, 0, 20);
			ctx.update(Util.hashString(SHA1.getInstance(), docname));
			System.arraycopy(ctx.digest(), 0, val, 0, 20);
		}
	}

	/**
	 * Create a new SVK from the given Storables and length
	 */
	public SVK(Storables storables, int log2size) throws KeyException {
		super(20, log2size, keyNumber);
		if (log2size > SVK_LOG2_MAXSIZE || !storables.isLegalForSVK())
			throw new KeyException("illegal Storables");

		Digest ctx = SHA1.getInstance();
		ctx.update(storables.getPublicKey().asBytes());
		System.arraycopy(ctx.digest(), 0, val, 0, 20);
		setRootValue(val);

		byte[] docname = storables.getDocumentName();
		if (docname != null) {
			ctx.update(val, 0, 20);
			ctx.update(docname);
			System.arraycopy(ctx.digest(), 0, val, 0, 20);
		}
	}

	private void setRootValue(byte[] val) {
		root = copyval(val);
	}

	public byte[] getRootValue() {
		return root;
	}

	/**
	 * Verifies a DSS-SVK by performing a signature verification.
	 */
	public VerifyingInputStream verifyStream(
		InputStream data,
		Storables storables,
		long transLength)
		throws DataNotValidIOException {

		if (!storables.isLegalForSVK() || !storables.verifies())
			throw new DataNotValidIOException(Presentation.CB_BAD_KEY);

		int log2size = val[20];
		long partSize = storables.getPartSize();
		long dataLength = getDataLength(transLength, partSize);

		if (log2size < LOG2_MINSIZE
			|| log2size > SVK_LOG2_MAXSIZE
			|| 1 << log2size < dataLength
			|| 1 << LOG2_MINSIZE > dataLength
			|| 1 << Util.log2(dataLength) != dataLength
			|| partSize != getPartSize(dataLength))
			throw new DataNotValidIOException(Presentation.CB_BAD_KEY);

		Digest ctx = SHA1.getInstance();
		ctx.update(storables.getPublicKey().asBytes());
		byte[] hash = ctx.digest();

		byte[] docname = storables.getDocumentName();
		if (docname != null) {
			ctx.update(hash);
			ctx.update(docname);
			hash = ctx.digest();
		}

		if (!freenet.crypt.Util.byteArrayEqual(hash, val, 0, 20))
			throw new DataNotValidIOException(Presentation.CB_BAD_KEY);

		return super.verifyStream(data, storables, transLength);
	}

	/**
	 * Simple array copying to work around weirdness with .clone() on arrays
	 * for some users.
	 */
	private static byte[] copyval(byte[] val) {
		byte[] r = new byte[val.length];
		System.arraycopy(val, 0, r, 0, val.length);
		return r;
	}
}
