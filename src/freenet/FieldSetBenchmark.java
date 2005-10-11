package freenet;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import freenet.support.io.ReadInputStream;

/**
 * Simple benchmark for FieldSet.
 * 
 * @author syoung
 */
public class FieldSetBenchmark {

	/** Approximately how many milliseconds to run the tests for. */
	private final static long runtime = 5 * 1000;

	public static void main(String[] args) throws IOException {
		System.out.println("Running...");

		// Create a test field set.
		StringBuffer sb = new StringBuffer(550000);
		for (int i = 0; i < 450; i++) {
			String prefix = "";
			if (i > 0) {
				// Make one without a prefix for the get() benchmark.
				prefix =
					Integer.toString((int) Math.random() * Integer.MAX_VALUE);
			}
			sb
				.append(prefix)
				.append("physical.tcp=foo.bar.cat.dog.blah.blah.example.com:52296\n")
				.append(prefix)
				.append("ARK.encryption=1234567890abcde123456782348175981758135135151353151351531351111\n")
				.append(prefix)
				.append("ARK.revision=1\n")
				.append(prefix)
				.append("signature=1234567890abcde123456782348175981758135135151353151235351531351111235612412142124\n")
				.append(prefix)
				.append("presentations=3,1\n")
				.append(prefix)
				.append("sessions=1\n")
				.append(prefix)
				.append("identity.y=1234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde1234567823481759817512351231231\n")
				.append(prefix)
				.append("identity.p=1234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde1234567823481759817512351231231\n")
				.append(prefix)
				.append("identity.g=1234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde1234567823481759817512351231231\n")
				.append(prefix)
				.append("version=Fred,0.6,1.49,6430\n");

		}
		sb.append("End\n\n");

		// Benchmark read

		ByteArrayInputStream bis =
			new ByteArrayInputStream(sb.toString().getBytes());
		int iterations = 0;
		long duration = 0;
		long start = System.currentTimeMillis();
		FieldSet fs;
		do {
			bis.reset();
			fs = new FieldSet(new ReadInputStream(bis));
			iterations++;
		} while ((duration = System.currentTimeMillis() - start) < runtime);

		double kbs = (iterations * sb.length() / 1024.0) / (duration / 1000.0);
		System.out.println("read: " + (int) kbs + " kb/s");

		// Benchmark get

		duration = 0;
		start = System.currentTimeMillis();
		do {
			// Do a few to reduce the impact of the timing overhead.
			for (int i = 0; i < 1000; i++) {
				fs.getSet("physical").getString("tcp");
			}
			iterations += 1000;
		} while ((duration = System.currentTimeMillis() - start) < runtime);
		System.out.println("get: " + iterations / duration + " operations/s");

		// Benchmark put

		duration = 0;
		start = System.currentTimeMillis();
		do {
			// Do a few to reduce the impact of the timing overhead.
			for (int i = 0; i < 1000; i++) {
				fs.put("somekey", "new value");
			}
			iterations += 1000;
		} while ((duration = System.currentTimeMillis() - start) < runtime);
		System.out.println("put: " + iterations / duration + " operations/s");
	}

}
