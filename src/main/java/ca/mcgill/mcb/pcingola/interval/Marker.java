package ca.mcgill.mcb.pcingola.interval;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ca.mcgill.mcb.pcingola.codons.CodonTable;
import ca.mcgill.mcb.pcingola.codons.CodonTables;
import ca.mcgill.mcb.pcingola.serializer.MarkerSerializer;
import ca.mcgill.mcb.pcingola.serializer.TxtSerializable;
import ca.mcgill.mcb.pcingola.snpEffect.EffectType;
import ca.mcgill.mcb.pcingola.snpEffect.VariantEffect;
import ca.mcgill.mcb.pcingola.snpEffect.VariantEffects;
import ca.mcgill.mcb.pcingola.util.Gpr;

/**
 * An interval intended as a mark
 *
 * @author pcingola
 */
public class Marker extends Interval implements TxtSerializable {

	private static final long serialVersionUID = 7878886900660027549L;
	protected EffectType type = EffectType.NONE;

	protected Marker() {
		super();
		type = EffectType.NONE;
	}

	public Marker(Marker parent, int start, int end) {
		this(parent, start, end, false, "");
	}

	public Marker(Marker parent, int start, int end, boolean strandMinus, String id) {
		super(parent, start, end, strandMinus, id);

		// Adjust parent if child is not included?
		if ((parent != null) && !parent.includes(this)) {
			String err = "";
			if (isShowWarningIfParentDoesNotInclude()) err = "WARNING: " + this.getClass().getSimpleName() + " is not included in parent " + parent.getClass().getSimpleName() + ". " //
					+ "\t" + this.getClass().getSimpleName() + " '" + getId() + "'  [ " + getStart() + " , " + getEnd() + " ]" //
					+ "\t" + parent.getClass().getSimpleName() + " '" + parent.getId() + "' [ " + parent.getStart() + " , " + parent.getEnd() + " ]";

			// Adjust parent?
			if (isAdjustIfParentDoesNotInclude(parent)) {
				parent.adjust(this);
				if (isShowWarningIfParentDoesNotInclude()) err += "\t=> Adjusting " + parent.getClass().getSimpleName() + " '" + parent.getId() + "' to [ " + parent.getStart() + " , " + parent.getEnd() + " ]";
			}

			// Show an error?
			if (isShowWarningIfParentDoesNotInclude()) System.err.println(err);
		}
	}

	/**
	 * Adjust [start,end] to include child
	 */
	protected void adjust(Marker child) {
		start = Math.min(start, child.getStart());
		end = Math.max(end, child.getEnd());
	}

	/**
	 * Apply a variant to a marker.
	 *
	 * Calculate a the result of a marker, such that
	 * 		newMarker = marker.apply( variant )
	 *		variant = Diff( newMarker , marker )		// Differences in sequence
	 *
	 * Note: This method may return:
	 *    - The same marker (this) when genetic coordinates remain unchanged
	 *    - 'null' if the whole marker is removed by the variant (e.g. a deletion spanning the whole marker)
	 *
	 * For these reasons, the method should never be invoked directly.
	 * This is why the method is 'private' and 'final'
	 *
	 * @return The marker result after applying variant
	 */
	public Marker apply(Variant variant) {
		if (!shouldApply(variant)) return this;

		// Negative strand variants are a pain. We will eventually get rid of them...(they do not make sense any more)
		if (variant.isStrandMinus()) throw new RuntimeException("Only variants in postive strand are suported!\n\tVariant : " + variant);

		Marker newMarker = null;
		switch (variant.getVariantType()) {
		case SNP:
		case MNP:
			// Variant does not change length?
			// No effect when applying (all coordinates remain the same)
			newMarker = this;
			break;

		case INS:
			int lenChange = variant.lengthChange();
			newMarker = applyIns(variant, lenChange);
			break;

		case DEL:
			lenChange = variant.lengthChange();
			newMarker = applyDel(variant, lenChange);
			break;

		case MIXED:
			lenChange = variant.lengthChange();
			newMarker = applyMixed(variant, lenChange);
			break;

		default:
			// We are not ready for mixed changes
			throw new RuntimeException("Variant type not supported: " + variant.getVariantType() + "\n\t" + variant);
		}

		// Always return a copy of the marker (if the variant is applied)
		if (newMarker == this) return cloneShallow();

		return newMarker;
	}

	/**
	 * Apply a Variant to a marker. Variant is a deletion
	 */
	protected Marker applyDel(Variant variant, int lenChange) {
		Marker m = cloneShallow();

		if (variant.getEnd() < start) {
			// Deletion before start: Adjust coordinates
			m.start += lenChange;
			m.end += lenChange;
		} else if (variant.includes(this)) {
			// Deletion completely includes this marker => The whole marker deleted
			return null;
		} else if (includes(variant)) {
			// This marker completely includes the deletion, but deletion does not include
			// marker. Marker is shortened (i.e. only 'end' coordinate needs to be updated)
			m.end += lenChange;
		} else {
			// Variant is partially included in this marker.
			// This is treated as three different type of deletions:
			//		1- One after the marker
			//		2- One inside the marker
			//		3- One before the marker
			// Note that type 1 and 3 cannot exists at the same time, otherwise the
			// deletion would fully include the marker (previous case)

			// Part 1: Deletion after the marker
			if (m.end < variant.getEnd()) {
				// Actually this does not affect the coordinates, so we don't care about this part
			}

			// Part 2: Deletion matching the marker (intersection)
			int istart = Math.max(variant.getStart(), m.start);
			int iend = Math.min(variant.getEnd(), m.end);
			if (iend < istart) throw new RuntimeException("This should never happen!"); // Sanity check
			m.end -= (iend - istart + 1); // Update end coordinate

			// Part 3: Deletion before the marker
			if (variant.getStart() < start) {
				// Update coordinates shifting the marker to the left
				int delta = m.start - variant.getStart();
				m.start -= delta;
				m.end -= delta;
			}
		}

		return m;
	}

	/**
	 * Apply a Variant to a marker. Variant is an insertion
	 */
	protected Marker applyIns(Variant variant, int lenChange) {
		Marker m = cloneShallow();

		if (variant.getStart() < start) {
			// Insertion point before marker start? => Adjust both coordinates
			m.start += lenChange;
			m.end += lenChange;
		} else if (variant.getStart() <= end) {
			// Insertion point after start, but before end? => Adjust end coordinate
			m.end += lenChange;
		} else {
			// Insertion point after end, no effect on marker coordinates
		}

		return m;
	}

	/**
	 * Apply a mixed variant
	 * MIXED variant is interpreted as "MNP + InDel" or "InDel + MNP"
	 */
	protected Marker applyMixed(Variant variant, int lenChange) {
		Marker m = cloneShallow();

		if (includes(variant)) {
			// This marker completely includes the variant.
			// Marker is shortened (i.e. only 'end' coordinate needs to be updated)
			m.end += lenChange;
		} else if (variant.includes(this)) {
			// Variant completely includes this marker => The whole marker
			// replaced but coordinates do not change (i.e. MNP + InDel )
			throw new RuntimeException("Unimplemented");
			// return m;
		} else if (variant.getStart() < start) {
			// Variant before start: Adjust coordinates
			// In this case we can see the variant as a InDel (at the
			// start coordinate) followed by a MNP.
			m.start += lenChange;
			m.end += lenChange;
		} else {
			// This marker includes the variant.
			// Marker is shortened (i.e. only 'end' coordinate needs to be updated)
			m.end += lenChange;
		}

		return m;
	}

	@Override
	public Marker clone() {
		return (Marker) super.clone();
	}

	/**
	 * Perform a shallow clone
	 */
	@SuppressWarnings("rawtypes")
	public Marker cloneShallow() {
		try {
			// Create new object
			Constructor ctor = this.getClass().getConstructor();
			Marker clone = (Marker) ctor.newInstance();

			// Copy fields
			clone.chromosomeNameOri = chromosomeNameOri;
			clone.end = end;
			clone.id = id;
			clone.parent = parent;
			clone.start = start;
			clone.strandMinus = strandMinus;
			clone.type = type;

			return clone;
		} catch (Exception e) {
			throw new RuntimeException("Error performing shallow clone: ", e);
		}
	}

	/**
	 * Get a suitable codon table
	 */
	public CodonTable codonTable() {
		return CodonTables.getInstance().getTable(getGenome(), getChromosomeName());
	}

	/**
	 * Compare by start and end
	 */
	@Override
	public int compareTo(Interval i2) {
		int comp = compareToPos(i2);
		if (comp != 0) return comp;

		// Compare by ID
		if ((id == null) && (i2.getId() == null)) return 0;
		if ((id != null) && (i2.getId() == null)) return -1;
		if ((id == null) && (i2.getId() != null)) return 1;
		return id.compareTo(i2.getId());
	}

	/**
	 * Compare genomic coordinates
	 */
	public int compareToPos(Interval i2) {
		// Compare chromosome names
		Marker m2 = (Marker) i2;

		Chromosome chr1 = getChromosome();
		Chromosome chr2 = m2.getChromosome();

		if ((chr1 != null) && (chr2 != null)) {
			// Non-null: Compare chromosomes
			int compChromo = chr1.compareChromoName(chr2);
			if (compChromo != 0) return compChromo;
		} else if ((chr1 == null) && (chr2 != null)) return 1; // One chromosome is null
		else if ((chr1 != null) && (chr2 == null)) return -1;

		// Compare by start position
		if (start > i2.start) return 1;
		if (start < i2.start) return -1;

		// Compare by end position
		if (end > i2.end) return 1;
		if (end < i2.end) return -1;

		return 0;
	}

	/**
	 * How far apart are these intervals?
	 * @return  Distance or -1 if they are not comparable (i.e. different chromosomes)
	 */
	public int distance(Marker interval) {
		if (!interval.getChromosomeName().equals(getChromosomeName())) return -1;

		if (intersects(interval)) return 0;

		if (start > interval.getEnd()) return start - interval.getEnd();
		if (interval.getStart() > end) return interval.getStart() - end;

		throw new RuntimeException("This should never happen!");
	}

	/**
	 * Distance from the beginning/end of a list of intervals, until this SNP
	 * It count the number of bases in 'markers'
	 */
	public int distanceBases(List<? extends Marker> markers, boolean fromEnd) {

		// Create a new list of sorted intervals
		ArrayList<Marker> markersSort = new ArrayList<Marker>();
		markersSort.addAll(markers);
		if (fromEnd) Collections.sort(markersSort, new IntervalComparatorByEnd(true));
		else Collections.sort(markersSort, new IntervalComparatorByStart());

		// Calculate distance
		int len = 0, latest = -1;
		for (Marker m : markersSort) {

			// Initialize
			if (latest < 0) {
				if (fromEnd) latest = m.getEnd() + 1;
				else latest = m.getStart() - 1;
			}

			if (fromEnd) {
				if (intersects(m)) return len + (m.getEnd() - start);
				else if (start > m.getEnd()) return len - 1 + (latest - start);

				latest = m.getStart();
			} else {
				if (intersects(m)) return len + (start - m.getStart());
				else if (start < m.getStart()) return len - 1 + (start - latest);

				latest = m.getEnd();
			}

			len += m.size();
		}

		if (fromEnd) return len - 1 + (latest - start);
		return len - 1 + (start - latest);
	}

	@Override
	public Marker getParent() {
		return (Marker) parent;
	}

	public EffectType getType() {
		return type;
	}

	public String idChain() {
		return idChain(";", true);
	}

	public String idChain(String separator, boolean useGeneId) {
		return idChain(separator, useGeneId, null);
	}

	/**
	 * A list of all IDs and parent IDs until chromosome
	 */
	public String idChain(String separator, boolean useGeneId, VariantEffect changeEffect) {
		StringBuilder sb = new StringBuilder();

		for (Marker m = this; (m != null) && !(m instanceof Chromosome) && !(m instanceof Genome); m = m.getParent()) {
			if (sb.length() > 0) sb.append(separator);

			switch (m.getType()) {
			case EXON:
				Transcript tr = (Transcript) m.getParent();
				Exon ex = (Exon) m;
				sb.append("exon_" + ex.getRank() + "_" + tr.numChilds() + "_" + ex.getSpliceType());
				break;

			case INTRON:
				Intron intron = (Intron) m;
				sb.append("intron_" + intron.getRank() + "_" + intron.getSpliceType());
				break;

			case GENE:
				Gene g = (Gene) m;
				sb.append(useGeneId ? m.getId() : g.getGeneName());
				sb.append(separator + g.getBioType());
				break;

			case TRANSCRIPT:
				sb.append(m.getId());
				sb.append(separator + ((Transcript) m).getBioType());
				break;

			case DOWNSTREAM:
				if ((changeEffect != null) && (changeEffect.getVariant() != null)) {
					Downstream downstream = (Downstream) m;
					sb.append(downstream.distanceToTr(changeEffect.getVariant()));
				}
				break;

			case UPSTREAM:
				if ((changeEffect != null) && (changeEffect.getVariant() != null)) {
					Upstream upstream = (Upstream) m;
					sb.append(upstream.distanceToTr(changeEffect.getVariant()));
				}
				break;

			case CHROMOSOME:
			case INTERGENIC:
				sb.append(m.getId());
				break;

			default:
				break;
			}
		}

		// Empty? Add ID
		if (sb.length() <= 0) sb.append(getId());

		// Prepend type
		sb.insert(0, this.getClass().getSimpleName() + separator);

		return sb.toString();
	}

	/**
	 * Is 'interval' completely included in 'this'?
	 * @return  return true if 'this' includes 'interval'
	 */
	public boolean includes(Marker marker) {
		if (!marker.getChromosomeName().equals(getChromosomeName())) return false;
		return (start <= marker.start) && (marker.end <= end);
	}

	/**
	 * Intersect of two markers
	 * @return A new marker which is the intersect of the two
	 */
	public Marker intersect(Marker marker) {
		if (!getChromosomeName().equals(marker.getChromosomeName())) return null;

		int istart = Math.max(start, marker.getStart());
		int iend = Math.min(end, marker.getEnd());
		if (iend < istart) return null;
		return new Marker(getParent(), istart, iend, strandMinus, "");
	}

	/**
	 * Adjust parent if it does not include child?
	 */
	protected boolean isAdjustIfParentDoesNotInclude(Marker parent) {
		return false;
	}

	/**
	 * Show an error if parent does not include child?
	 */
	protected boolean isShowWarningIfParentDoesNotInclude() {
		return false;
	}

	/**
	 * Return the difference between two markers
	 *
	 * @param interval
	 * @return A set of 'markers'. Note that the result can have zero, one or two markers
	 */
	public Markers minus(Marker interval) {
		Markers ints = new Markers();
		if (intersects(interval)) {
			if ((interval.getStart() <= getStart()) && (getEnd() <= interval.getEnd())) {
				// 'this' is included in 'interval' => Nothing left
			} else if ((interval.getStart() <= getStart()) && (interval.getEnd() < getEnd())) {
				// 'interval' overlaps left part of 'this' => Include right part of 'this'
				ints.add(new Marker(getParent(), interval.getEnd() + 1, getEnd(), isStrandMinus(), getId()));
			} else if ((getStart() < interval.getStart()) && (getEnd() <= interval.getEnd())) {
				// 'interval' overlaps right part of 'this' => Include left part of 'this'
				ints.add(new Marker(getParent(), getStart(), interval.getStart() - 1, isStrandMinus(), getId()));
			} else if ((getStart() < interval.getStart()) && (interval.getEnd() < getEnd())) {
				// 'interval' overlaps middle of 'this' => Include left and right part of 'this'
				ints.add(new Marker(getParent(), getStart(), interval.getStart() - 1, isStrandMinus(), getId()));
				ints.add(new Marker(getParent(), interval.getEnd() + 1, getEnd(), isStrandMinus(), getId()));
			} else throw new RuntimeException("Interval intersection not analysed. This should nbever happen!");
		} else ints.add(this); // No intersection => Just add 'this' interval

		return ints;
	}

	/**
	 * Query all genomic regions that intersect 'marker' (this makes sense in Gene, Transcript, Exon, etc.)
	 */
	public Markers query(Marker marker) {
		return null;
	}

	/**
	 * Parse a line (form a file)
	 * Format: "chromosome \t start \t end \t id \n"
	 */
	public void readTxt(String line, int lineNum, Genome genome, int positionBase) {
		line = line.trim(); // Remove spaces

		// Ignore empty lines and comment lines
		if ((line.length() > 0) && (!line.startsWith("#"))) {
			// Parse line
			String fields[] = line.split("\\s+");

			// Is line OK?
			if (fields.length >= 3) {
				Chromosome chromo = genome.getChromosome(fields[0].trim());
				if (chromo == null) System.err.println("WARNING: Chromosome '" + fields[0] + "' not found in genome '" + genome.getGenomeName() + "', version '" + genome.getVersion() + "'!\n\tLine: " + lineNum + "\t'" + line + "'");
				parent = chromo;
				start = Gpr.parseIntSafe(fields[1]) - positionBase;
				end = Gpr.parseIntSafe(fields[2]) - positionBase;
				id = "";

				if (fields.length >= 4) {
					// Join all ids using a space character (and remove all spaces)
					for (int t = 3; t < fields.length; t++)
						id += fields[t].trim() + " ";

					id = id.trim();
				}
			} else throw new RuntimeException("Error line " + lineNum + " (number of fields is " + fields.length + "):\t" + line);
		}
	}

	/**
	 * Parse a line from a serialized file
	 */
	@Override
	public void serializeParse(MarkerSerializer markerSerializer) {
		type = EffectType.valueOf(markerSerializer.getNextField());
		markerSerializer.getNextFieldInt();
		parent = new MarkerParentId(markerSerializer.getNextFieldInt()); // Create a 'fake' parent. It will be replaced after all objects are in memory.
		start = markerSerializer.getNextFieldInt();
		end = markerSerializer.getNextFieldInt();
		id = markerSerializer.getNextField();
		strandMinus = markerSerializer.getNextFieldBoolean();
	}

	//	/**
	//	 * Reset marker dependencies (e.g. sub-markers)
	//	 * Typically called after a 'Marker.apply(Variant)'
	//	 */
	//	public void reset() {
	//		// Default: Do nithing
	//	}

	/**
	 * Create a string to serialize to a file
	 */
	@Override
	public String serializeSave(MarkerSerializer markerSerializer) {
		return type //
				+ "\t" + markerSerializer.getIdByMarker(this) //
				+ "\t" + (parent != null ? markerSerializer.getIdByMarker((Marker) parent) : -1) //
				+ "\t" + start //
				+ "\t" + end //
				+ "\t" + id //
				+ "\t" + strandMinus //
				;
	}

	/**
	 * True if the variant should be applied to the marker
	 */
	public boolean shouldApply(Variant variant) {
		// Variant after this marker: No effect when applying (all coordinates remain the same)
		return variant.getStart() <= end;
	}

	/**
	 * To string as a simple "chr:start-end" format
	 */
	public String toStr() {
		return getClass().getSimpleName() + "_" + getChromosomeName() + ":" + (start + 1) + "-" + (end + 1);
	}

	@Override
	public String toString() {
		return getChromosomeName() + "\t" + start + "-" + end //
				+ " " //
				+ type + ((id != null) && (id.length() > 0) ? " '" + id + "'" : "");
	}

	/**
	 * Union of two markers
	 * @return A new marker which is the union of the two
	 */
	public Marker union(Marker m) {
		if (!getChromosomeName().equals(m.getChromosomeName())) return null;

		int ustart = Math.min(start, m.getStart());
		int uend = Math.max(end, m.getEnd());
		return new Marker(getParent(), ustart, uend, strandMinus, "");
	}

	/**
	 * Calculate the effect of this variant
	 */
	public boolean variantEffect(Variant variant, VariantEffects variantEffects) {
		if (!intersects(variant)) return false;
		variantEffects.add(variant, this, type, "");
		return true;
	}

	/**
	 * Calculate the effect of this variant
	 * @param variantRef :	Before analyzing results, we have to change markers using variantrRef 
	 * 						to create a new reference 'on the fly'
	 */
	public boolean variantEffectNonRef(Variant variant, VariantEffects variantEffects) {
		if (!intersects(variant)) return false; // Sanity check

		if (variant.isNonRef()) {
			Variant variantRef = ((VariantNonRef) variant).getVariantRef();
			Marker newMarker = apply(variantRef);

			// Has the marker been deleted?
			// Then there is no effect over this marker (it does not exist any more)
			if (newMarker == null) return false;

			return newMarker.variantEffect(variant, variantEffects);
		}

		return variantEffect(variant, variantEffects);
	}

}
