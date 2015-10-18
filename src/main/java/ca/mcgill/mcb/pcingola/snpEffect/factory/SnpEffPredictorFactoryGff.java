package ca.mcgill.mcb.pcingola.snpEffect.factory;

import java.io.BufferedReader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import ca.mcgill.mcb.pcingola.interval.BioType;
import ca.mcgill.mcb.pcingola.interval.Cds;
import ca.mcgill.mcb.pcingola.interval.Chromosome;
import ca.mcgill.mcb.pcingola.interval.Exon;
import ca.mcgill.mcb.pcingola.interval.FrameType;
import ca.mcgill.mcb.pcingola.interval.Gene;
import ca.mcgill.mcb.pcingola.interval.GffMarker;
import ca.mcgill.mcb.pcingola.interval.IntergenicConserved;
import ca.mcgill.mcb.pcingola.interval.IntronConserved;
import ca.mcgill.mcb.pcingola.interval.Marker;
import ca.mcgill.mcb.pcingola.interval.Transcript;
import ca.mcgill.mcb.pcingola.interval.TranscriptSupportLevel;
import ca.mcgill.mcb.pcingola.interval.Utr3prime;
import ca.mcgill.mcb.pcingola.interval.Utr5prime;
import ca.mcgill.mcb.pcingola.snpEffect.Config;
import ca.mcgill.mcb.pcingola.snpEffect.SnpEffectPredictor;
import ca.mcgill.mcb.pcingola.util.Gpr;

/**
 * This class creates a SnpEffectPredictor from a GFF file.
 * This includes derived formats as GTF.
 *
 * References: http://gmod.org/wiki/GFF3
 *
 * @author pcingola
 */
public abstract class SnpEffPredictorFactoryGff extends SnpEffPredictorFactory {

	public static final String FASTA_DELIMITER = "##FASTA";

	String version = "";
	boolean mainFileHasFasta = false; // Are sequences in the GFF file or in a separate FASTA file?

	public SnpEffPredictorFactoryGff(Config config) {
		super(config, 1);
		markersById = new HashMap<String, Marker>();
		genesById = new HashMap<String, Gene>();
		transcriptsById = new HashMap<String, Transcript>();
		fileName = config.getBaseFileNameGenes() + ".gff";

		frameCorrection = true;
		frameType = FrameType.GFF;
	}

	/**
	 * Create a new exon
	 */
	protected Exon addExon(Transcript tr, GffMarker gffMarker, String exonId) {
		int rank = 0; // Rank information is added later
		Exon ex = new Exon(tr, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), exonId, rank);
		ex.setFrame(gffMarker.getFrame());
		add(ex);
		return ex;
	}

	/**
	 * Create and add a new exon
	 */
	protected List<Exon> addExons(GffMarker gffMarker) {
		String id = gffMarker.getId();
		List<Exon> list = new LinkedList<>();

		// Add exon to each parent (can belong to more than one transcript)
		for (String parentId : gffMarker.getGffParentIds()) {
			parentId = parentId.trim();

			// Exon's parent (should be a transcript)
			Transcript tr = findTranscript(parentId, id);
			Gene gene = findGene(parentId);

			// Is exon's parent a gene instead of a transcript?
			if ((tr == null) && (gene != null)) {
				// Create a transcript from the gene
				String trId = "Transcript_" + gene.getId(); // Transcript ID
				tr = addTranscript(gene, gffMarker, trId);
				if (debug) warning("Exon's parent '" + parentId + "' is a Gene instead of a transcript. Created transcript '" + tr.getId() + "' for this exon.");
			}

			// Try to find the gene
			if (gene == null) gene = findGene(gffMarker.getGeneId());

			// No transcript found? => Try creating one
			if (tr == null) {
				// No gene? Create one
				if (gene == null) gene = addGene(gffMarker);

				// Create transcript
				String trId = parentId.isEmpty() ? "Transcript_" + id : parentId; // Transcript ID
				tr = addTranscript(gene, gffMarker, trId);

				// Add gene & transcript
				if (debug) warning("Cannot find transcript '" + parentId + "'. Created transcript '" + tr.getId() + "' and gene '" + gene.getId() + "' for this exon");
			}

			// This can be added in different ways
			int rank = 0; // Rank information is added later
			switch (gffMarker.getGffType()) {
			case EXON:
				Exon ex = new Exon(tr, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), id, rank);
				ex.setFrame(gffMarker.getFrame());
				add(ex);
				list.add(ex);
				break;

			case CDS:
				Cds cds = new Cds(tr, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), id);
				cds.setFrame(gffMarker.getFrame());
				add(cds);
				break;

			case START_CODON:
			case STOP_CODON:
				ex = new Exon(tr, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), id, rank);
				ex.setFrame(gffMarker.getFrame());
				add(ex);

				cds = new Cds(tr, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), gffMarker.getGffType() + "_" + id);
				cds.setFrame(gffMarker.getFrame());
				add(cds);
				break;

			default:
				throw new RuntimeException("Unsupported type " + gffMarker.getGffType());
			}

		}

		return list.isEmpty() ? null : list;
	}

	/**
	 * Create and add a gene based on GffMarker
	 */
	protected Gene addGene(GffMarker gffMarker) {
		BioType bioType = gffMarker.getGeneBiotype();

		Gene gene = new Gene(gffMarker.getChromosome() //
		, gffMarker.getStart() //
		, gffMarker.getEnd() //
		, gffMarker.isStrandMinus() //
		, gffMarker.getGeneId() //
		, gffMarker.getGeneName() //
		, bioType);

		add(gene);

		return gene;
	}

	/**
	 * Add an intergenic conserved region
	 */
	protected IntergenicConserved addIntergenicConserved(GffMarker gffMarker) {
		IntergenicConserved intergenicConserved = new IntergenicConserved(gffMarker.getChromosome(), gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), gffMarker.getId());
		add(intergenicConserved);
		return intergenicConserved;
	}

	/**
	 * Add interval based on GffMarker data
	 * @return true if on success
	 */
	protected boolean addInterval(GffMarker gffMarker) {

		switch (gffMarker.getGffType()) {
		case GENE:
			// Sanity check: Have we already added this one?
			String geneId = gffMarker.getGeneId();
			if ((geneId != null) && (findGene(geneId) != null)) {
				warning("Gene '" + geneId + "' already added");
				return false;
			}

			return findOrCreateGene(gffMarker) != null;

		case TRANSCRIPT:
			// Sanity check: Have we already added this one?
			String trId = gffMarker.getTranscriptId();
			if ((trId != null) && (findTranscript(trId) != null)) {
				warning("Transcript '" + trId + "' already added");
				return false;
			}

			return findOrCreateTranscript(gffMarker) != null;

		case CDS:
			return addExons(gffMarker) != null;

		case EXON:
		case STOP_CODON:
		case START_CODON:
			return addExons(gffMarker) != null;

		case UTR5:
			return addUtr5(gffMarker) != null;

		case UTR3:
			return addUtr3(gffMarker) != null;

		case INTRON_CONSERVED:
			return addIntronConserved(gffMarker) != null;

		case INTERGENIC_CONSERVED:
			return addIntergenicConserved(gffMarker) != null;

		default:
			return false;
		}
	}

	/**
	 * Add an intron conserved region
	 */
	protected IntronConserved addIntronConserved(GffMarker gffMarker) {
		String trId = gffMarker.getTranscriptId();

		// Find transcript
		Transcript tr = findTranscript(trId);
		if (tr == null) tr = findTranscript(gffMarker.getId());
		if (tr == null) {
			warning("Could not find transcript '" + trId + "'");
			return null;
		}

		IntronConserved intronConserved = new IntronConserved(tr, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), gffMarker.getId());
		add(intronConserved);
		return intronConserved;
	}

	/**
	 * Create and add transcript
	 */
	Transcript addTranscript(Gene gene, GffMarker gffMarker, String trId) {
		Transcript tr = new Transcript(gene, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), trId);

		// Set protein coding (if available)
		if (gffMarker.isProteingCoding()) tr.setProteinCoding(true);

		// Biotype
		tr.setBioType(gffMarker.getTranscriptBiotype());

		// Transcript support level  (TSL)
		String tslStr = gffMarker.getAttr("transcript_support_level");
		if (tslStr != null) tr.setTranscriptSupportLevel(TranscriptSupportLevel.parse(tslStr));

		// Transcript version
		String ver = gffMarker.getTranscriptVersion();
		if (ver != null) tr.setVersion(ver);

		// Add transcript
		add(tr);

		//---
		// Sanity check and updates for gene
		//---

		// Update gene bio-type (if needed)
		BioType geneBioType = gffMarker.getGeneBiotype();
		if (gene.getBioType() == null && geneBioType != null) gene.setBioType(geneBioType);

		// Check that gene and transcript are in the same chromosome
		if (!gene.getChromosomeName().equals(tr.getChromosomeName())) {
			error("Trying to assign Transcript to a Gene in a different chromosome!" //
					+ "\n\tTranscript : " + tr.toStr()//
					+ "\n\tGene       : " + gene.toStr() //
			);
		}

		return tr;
	}

	/**
	 * Create new UTR3primes
	 */
	protected List<Utr3prime> addUtr3(GffMarker gffMarker) {
		List<Utr3prime> list = new LinkedList<>();

		// Add to each parent (can belong to more than one transcript)
		for (String parentId : gffMarker.getGffParentIds()) {

			// Find exon & transcript
			Exon exon = findOrCreateExon(parentId, gffMarker);
			if (exon != null) {
				Transcript tr = (Transcript) exon.getParent();

				// Create UTR
				Utr3prime u3 = new Utr3prime(exon, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), gffMarker.getId());
				tr.add(u3);
				add(u3);
				list.add(u3);
			} else warning("Could not add UTR");
		}

		return list.isEmpty() ? null : list;
	}

	/**
	 * Create UTR5primes
	 */
	protected List<Utr5prime> addUtr5(GffMarker gffMarker) {
		List<Utr5prime> list = new LinkedList<>();

		// Add to each parent (can belong to more than one transcript)
		for (String parentId : gffMarker.getGffParentIds()) {
			// Find exon & transcript
			Exon exon = findOrCreateExon(parentId, gffMarker);
			if (exon != null) {
				Transcript tr = (Transcript) exon.getParent();

				// Create UTR
				Utr5prime u5 = new Utr5prime(exon, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), gffMarker.getId());
				tr.add(u5);
				add(u5);
				list.add(u5);
			} else warning("Could not add UTR");
		}

		return list.isEmpty() ? null : list;
	}

	@Override
	public SnpEffectPredictor create() {
		// Read gene intervals from a file
		if (verbose) System.out.println("Reading " + version + " data file  : '" + fileName + "'");
		try {
			readGff();

			// Some clean-up before reading exon sequences
			beforeExonSequences();

			if (readSequences) readExonSequences();
			else if (createRandSequences) createRandSequences();

			if (verbose) System.out.println("\tTotal: " + totalSeqsAdded + " sequences added, " + totalSeqsIgnored + " sequences ignored.");

			// Finish up (fix problems, add missing info, etc.)
			finishUp();

			if (verbose) System.out.println(config.getGenome());
		} catch (Exception e) {
			if (verbose) e.printStackTrace();
			throw new RuntimeException("Error reading file '" + fileName + "'\n" + e);
		}

		return snpEffectPredictor;
	}

	/**
	 * Find an exon for a given parentId
	 */
	protected Exon findOrCreateExon(String parentId, GffMarker gffMarker) {
		// Get transcript
		Transcript tr = findTranscript(parentId);
		if (tr == null) tr = findTranscript(gffMarker.getTranscriptId());
		if (tr == null) {
			warning("Cannot find transcript '" + parentId + "'");
			return null;
		}

		// Find exon using coordinates
		Marker utr = new Marker(tr, gffMarker.getStart(), gffMarker.getEnd(), gffMarker.isStrandMinus(), gffMarker.getId());
		Exon exon = tr.queryExon(utr);
		if (exon != null) return exon;

		// Nothing found? Create exon
		exon = addExon(tr, gffMarker, gffMarker.getId());
		if (debug) warning("Cannot find exon for UTR: '" + utr.getId() + "'. Creating exon '" + gffMarker.getId() + "'");
		return exon;
	}

	/**
	 * Find or create a gene based on GffMarker
	 */
	protected Gene findOrCreateGene(GffMarker gffMarker) {
		// Find gene
		Gene gene = findGene(gffMarker.getGeneId());
		if (gene == null) gene = findGene(gffMarker.getId());

		// Add gene if needed
		if (gene == null) gene = addGene(gffMarker);

		return gene;
	}

	/**
	 * Create and add a transcript based on GffMarker
	 */
	protected Transcript findOrCreateTranscript(GffMarker gffMarker) {
		// Add transcript
		String trId = gffMarker.getTranscriptId();
		Transcript tr = findTranscript(trId);
		if (tr == null) tr = findTranscript(gffMarker.getId());

		if (tr == null) {
			// Add gene if needed
			Gene gene = findOrCreateGene(gffMarker);
			tr = addTranscript(gene, gffMarker, trId);
		}

		return tr;
	}

	/**
	 * Parse a line
	 * @return true if a line was parsed
	 */
	protected boolean parse(String line) {
		GffMarker gffMarker = new GffMarker(genome, line);
		return addInterval(gffMarker);
	}

	@Override
	protected void readExonSequences() {
		// Read chromosome sequences and set exon sequences
		if (verbose) System.out.print("\tReading sequences   :\n");
		if (mainFileHasFasta) readExonSequencesGff(fileName); // Read from GFF file (it has a '##FASTA' delimiter)
		else super.readExonSequences(); // Read them from FASTA file
	}

	/**
	 * Read chromosome sequence from GFF3 file and extract exons' sequences
	 */
	protected void readExonSequencesGff(String gffFileName) {
		try {
			BufferedReader reader = Gpr.reader(gffFileName);

			// Get to fasta part of the file
			for (lineNum = 1; reader.ready(); lineNum++) {
				line = reader.readLine();
				if (line.equals(FASTA_DELIMITER)) {
					mainFileHasFasta = true;
					break;
				}
			}

			// Read fasta sequence
			String chromoName = null;
			StringBuffer chromoSb = new StringBuffer();
			for (; reader.ready(); lineNum++) {
				line = reader.readLine();
				if (line.startsWith(">")) { // New fasta sequence
					// Set chromosome sequences and length (create it if it doesn't exist)
					if (chromoName != null) addSequences(chromoName, chromoSb.toString()); // Add all sequences

					// Get sequence name
					int idxSpace = line.indexOf(' ');
					if (idxSpace > 0) line = line.substring(0, idxSpace);
					chromoName = Chromosome.simpleName(line.substring(1).trim()); // New chromosome name
					chromoNamesReference.add(chromoName);

					// Initialize buffer
					chromoSb = new StringBuffer();
					if (verbose) System.out.println("\t\tReading sequence '" + chromoName + "'");
				} else chromoSb.append(line.trim());
			}

			// Last chromosome
			// Set chromosome sequneces and length (create it if it doesn't exist)
			if (chromoName != null) {
				chromoLen(chromoName, chromoSb.length());
				addSequences(chromoName, chromoSb.toString()); // Add all sequences
			} else warning("Ignoring sequences for '" + chromoName + "'. Cannot find chromosome"); // Chromosome not found

			reader.close();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Read GFF file from the beginning looking for 'typeToRead' elements
	 */
	protected void readGff() throws Exception {
		int count = 0;
		BufferedReader reader = Gpr.reader(fileName);
		if (reader == null) return; // Error

		// Parsing GFF3 (reference: http://gmod.org/wiki/GFF3)
		try {
			for (lineNum = 1; reader.ready(); lineNum++) {
				line = reader.readLine();

				// Are we done?
				if (line.equals(FASTA_DELIMITER)) {
					mainFileHasFasta = true;
					break;
				} else if (line.startsWith("#")) {
					// Ignore this line
				} else if (parse(line)) {
					count++;
					if (verbose) Gpr.showMark(count, MARK, "\t\t");
				}
			}
		} catch (Exception e) {
			error("Offending line (lineNum: " + lineNum + "): '" + line + "'", e);
		}

		reader.close();
		if (verbose) System.out.println((count > 0 ? "\n" : "") + "\tTotal: " + count + " markers added.");
	}
}
