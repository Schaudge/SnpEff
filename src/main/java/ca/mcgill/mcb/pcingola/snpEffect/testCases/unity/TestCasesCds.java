package ca.mcgill.mcb.pcingola.snpEffect.testCases.unity;

import org.junit.Test;

import ca.mcgill.mcb.pcingola.interval.Exon;
import ca.mcgill.mcb.pcingola.interval.Gene;
import ca.mcgill.mcb.pcingola.interval.Transcript;
import ca.mcgill.mcb.pcingola.interval.Utr5prime;
import ca.mcgill.mcb.pcingola.util.Gpr;
import junit.framework.Assert;

/**
 * Test random SNP changes
 *
 * @author pcingola
 */
public class TestCasesCds extends TestCasesBase {

	public static int N = 1000;

	public TestCasesCds() {
		super();
	}

	@Override
	protected void init() {
		super.init();
		randSeed = 20120131;
	}

	@Test
	public void test_CdsPos() {
		Gpr.debug("Test");

		// Test N times:
		//		- Create a random gene transcript, exons
		// 		- Cal
		for (int iter = 0; iter < N; iter++) {
			initSnpEffPredictor();
			if (debug) System.err.println("Test CDS pos iteration: " + iter + "\n" + transcript);
			else if (verbose) System.err.println("Test CDS pos iteration: " + iter + "\t" + transcript.getStrand() + "\t" + transcript.cds());
			else Gpr.showMark(iter + 1, 1);

			int cdsBaseNum = 0;
			int cds2pos[] = transcript.baseNumberCds2Pos();

			// For each exon...
			for (Exon exon : transcript.sortedStrand()) {
				// Iterate on each base and compare CDS positon with calculated one
				int min = transcript.isStrandPlus() ? exon.getStart() : exon.getEnd();
				int step = transcript.isStrandPlus() ? 1 : -1;

				for (int pos = min; exon.intersects(pos); pos += step, cdsBaseNum++) {
					int cdsBaseNumCalc = transcript.baseNumberCds(pos, true);

					// Is it OK?
					Assert.assertEquals(cdsBaseNum, cdsBaseNumCalc);
					Assert.assertEquals(pos, cds2pos[cdsBaseNum]);
				}
			}
		}
		System.err.println("");
	}

	/**
	 * Simple CDS start & CSD end case
	 */
	@Test
	public void test_cdsStartEnd_1() {
		Gpr.debug("Test");
		Gene g = new Gene(chromosome, 0, 100, false, "g1", "g1", null);
		Transcript tr = new Transcript(g, 10, 100, false, "tr1");

		Exon e1 = new Exon(tr, 10, 30, false, "e1", 1);
		Exon e2 = new Exon(tr, 50, 80, false, "e2", 2);
		Exon e3 = new Exon(tr, 90, 100, false, "e3", 3);

		tr.add(e1);
		tr.add(e2);
		tr.add(e3);

		Assert.assertEquals(10, tr.getCdsStart());
		Assert.assertEquals(100, tr.getCdsEnd());
		if (verbose) System.out.println("Transcript : " + tr);
		if (verbose) System.out.println("CDS.start: " + tr.getCdsStart() + "\tCDS.end: " + tr.getCdsEnd());
	}

	/**
	 * CDS start & CSD end case where transcript is ALL UTR (nothing codes, presumably because of a database annotation error)
	 */
	@Test
	public void test_cdsStartEnd_2() {
		Gpr.debug("Test");
		Gene g = new Gene(chromosome, 10, 100, false, "g1", "g1", null);
		Transcript tr = new Transcript(g, 10, 100, false, "tr1");

		Exon e1 = new Exon(tr, 10, 30, false, "e1", 1);
		Exon e2 = new Exon(tr, 50, 80, false, "e2", 2);
		Exon e3 = new Exon(tr, 90, 100, false, "e3", 3);

		tr.add(e1);
		tr.add(e2);
		tr.add(e3);

		Utr5prime u1 = new Utr5prime(e1, 10, 30, false, "u1");
		Utr5prime u2 = new Utr5prime(e2, 50, 80, false, "u2");
		Utr5prime u3 = new Utr5prime(e3, 90, 100, false, "u3");

		tr.add(u1);
		tr.add(u2);
		tr.add(u3);

		Assert.assertEquals(10, tr.getCdsStart());
		Assert.assertEquals(10, tr.getCdsEnd());
		if (verbose) System.out.println("Transcript : " + tr);
		if (verbose) System.out.println("CDS.start: " + tr.getCdsStart() + "\tCDS.end: " + tr.getCdsEnd());
	}

}
