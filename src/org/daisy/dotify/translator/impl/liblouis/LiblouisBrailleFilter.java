package org.daisy.dotify.translator.impl.liblouis;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.daisy.dotify.api.hyphenator.HyphenatorConfigurationException;
import org.daisy.dotify.api.hyphenator.HyphenatorFactoryMakerService;
import org.daisy.dotify.api.hyphenator.HyphenatorInterface;
import org.daisy.dotify.api.translator.BrailleFilter;
import org.daisy.dotify.api.translator.TextAttribute;
import org.daisy.dotify.api.translator.Translatable;
import org.daisy.dotify.api.translator.TranslatableWithContext;
import org.daisy.dotify.api.translator.TranslationException;
import org.daisy.dotify.api.translator.TranslatorSpecification;
import org.liblouis.CompilationException;
import org.liblouis.DisplayException;
import org.liblouis.DisplayTable.Fallback;
import org.liblouis.DisplayTable.UnicodeBrailleDisplayTable;
import org.liblouis.TranslationResult;
import org.liblouis.Translator;

class LiblouisBrailleFilter implements BrailleFilter {
	private static final int SOFT_HYPHEN = 0x00ad;
	private static final int ZERO_WIDTH_SPACE = 0x200b;
	private static final int LIBLOUIS_NO_BREAKPOINT = 0;
	private static final int LIBLOUIS_SOFT_HYPEN = 1;
	private static final int LIBLOUIS_ZERO_WIDTH_SPACE = 2;
	private static final Map<String, Integer> MARKERS = makeMarkersMap();
	private final String loc;
	private final HyphenatorFactoryMakerService hyphenatorFactoryMaker;
	private final Map<String, HyphenatorInterface> hyphenators;
	private final Translator table;

	LiblouisBrailleFilter(TranslatorSpecification ts, HyphenatorFactoryMakerService hyphenatorFactoryMaker) {
		this.loc = ts.getLocale();
		this.hyphenatorFactoryMaker = hyphenatorFactoryMaker;
		this.hyphenators = new HashMap<>();
		try {
			table = new Translator(LiblouisSpecifications.getMap().get(ts));
		} catch (CompilationException e) {
			throw new IllegalArgumentException(e);
		}
	}

	static Map<String, Integer> makeMarkersMap() {
		Map<String, Integer> ret = new HashMap<>();
		ret.put("em", 1);
		ret.put("strong", 4);
		return Collections.unmodifiableMap(ret);
	}

	@Override
	public String filter(Translatable specification) throws TranslationException {
		if (specification.getText().isEmpty()) {
			return "";
		}
		String locale = specification.getLocale();
		if (locale==null) {
			locale = loc;
		}
		HyphenatorInterface h = hyphenators.get(locale);
		if (h == null && specification.isHyphenating()) {
			// if we're not hyphenating the language in question, we do not
			// need to add it, nor throw an exception if it cannot be found.
			try {
				h = hyphenatorFactoryMaker.newHyphenator(locale);
			} catch (HyphenatorConfigurationException e) {
				throw new LiblouisBrailleFilterException(e);
			}
			hyphenators.put(locale, h);
		}
		String str = specification.isHyphenating()?h.hyphenate(specification.getText()):specification.getText();
		//translate braille using the same filter, regardless of language
		LiblouisTranslatable louisSpec = toLiblouisSpecification(str, specification, MARKERS);
		try {
			return toBrailleFilterString(louisSpec.getText(), table.translate(louisSpec.getText(), louisSpec.getTypeForm(), louisSpec.getCharAtts(), louisSpec.getInterCharAtts(), new UnicodeBrailleDisplayTable(Fallback.MASK)));
		} catch (org.liblouis.TranslationException | DisplayException e) {
			throw new LiblouisBrailleFilterException(e);
		}
	}
	
	@Override
	public String filter(TranslatableWithContext specification) throws TranslationException {
		String locale = specification.getLocale().orElse(loc);
		
		Stream<String> texts = specification.getText().stream().map(v->v.resolve());
		
		if (!specification.shouldMarkCapitalLetters()) {
			//TODO: toLowerCase may not always do what we want here,
			//it depends on the lower case algorithm and the rules 
			//of the braille for that language
			texts = texts.map(v->v.toLowerCase(Locale.ROOT));
		}
		
		List<String> textsList = texts.collect(Collectors.toList());
		String strIn = textsList.stream().collect(Collectors.joining());
		Translatable.Builder tr = Translatable.text(strIn)
				.locale(locale)
				.hyphenate(specification.shouldHyphenate())
				.markCapitalLetters(specification.shouldMarkCapitalLetters());

		String strHyph;
		if (specification.shouldHyphenate()) {
			HyphenatorInterface h = hyphenators.get(locale);
			if (h == null) {
				try {
					h = hyphenatorFactoryMaker.newHyphenator(locale);
				} catch (HyphenatorConfigurationException e) {
					throw new LiblouisBrailleFilterException(e);
				}
				hyphenators.put(locale, h);
			}
			HyphenatorInterface h2 = h;
			strHyph = textsList.stream().map(v->h2.hyphenate(v)).collect(Collectors.joining());
		} else {
			strHyph = strIn;
		}
		//FIXME: this doesn't work at all, because the Attribute with context extends beyond the texts
		//in the list. 
		//specification.getAttributes()
		//	.ifPresent(att->tr.attributes(DefaultMarkerProcessor.toTextAttribute(att, textsList)));
		
		//translate braille using the same filter, regardless of language
		LiblouisTranslatable louisSpec = toLiblouisSpecification(strHyph, tr.build(), MARKERS);
		try {
			return toBrailleFilterString(louisSpec.getText(), table.translate(louisSpec.getText(), louisSpec.getTypeForm(), louisSpec.getCharAtts(), louisSpec.getInterCharAtts(), new UnicodeBrailleDisplayTable(Fallback.MASK)));
		} catch (org.liblouis.TranslationException | DisplayException e) {
			throw new LiblouisBrailleFilterException(e);
		}
	}

	/**
	 * Maps a translatable and the corresponding hyphenated string to a set of data that can be 
	 * used with Liblouis. The hyphenated string is used to set the intercharacter attributes.
	 * The map is used for creating a type form array from the translatable's text attributes.
	 * 
	 * @param hyphStr the hyphenated string
	 * @param spec the translatable
	 * @param map the "type form" map, may be null
	 * @return hyphenation information
	 */
	static LiblouisTranslatable toLiblouisSpecification(String hyphStr, Translatable spec, Map<String, Integer> map) {
		String inputStr = spec.getText();
		if (hyphStr.length() < inputStr.length()) {
			throw new IllegalArgumentException("The hyphenated string cannot be shorter than the input string");
		}
		TextAttribute ta = spec.getAttributes();
		int[] cpHyph = hyphStr.codePoints().toArray();
		int[] cpInput = inputStr.codePoints().toArray();
		int j=0;
		int flag;
		int[] interCharAttr = new int[cpInput.length-1];
		int[] charAtts = new int[cpInput.length];
		short[] typeForm;
		if (ta==null || map==null) {
			typeForm = new short[cpInput.length];
		} else {
			typeForm = toTypeForm(spec.getAttributes(), map);
		}
		for (int i=0; i<cpInput.length; i++) {
			charAtts[i]=i;
			flag = LIBLOUIS_NO_BREAKPOINT;
			while (j<cpHyph.length && i<cpInput.length-1 && cpInput[i+1]!=cpHyph[j]) {
				if (cpHyph[j]==SOFT_HYPHEN) {
					flag = LIBLOUIS_SOFT_HYPEN;
				} else if (cpHyph[j]==ZERO_WIDTH_SPACE && flag!=LIBLOUIS_SOFT_HYPEN) {
					flag = LIBLOUIS_ZERO_WIDTH_SPACE;
				} else if (cpInput[i]!=cpHyph[j] && cpInput[i+1]!=cpHyph[j+1]) {
					throw new RuntimeException("'"+hyphStr + ":" + inputStr+"'");
				}
				j++;
			}
			j++;
			if (i<cpInput.length-1) {
				interCharAttr[i] = flag;
			}
		}
		return new LiblouisTranslatable(inputStr, charAtts, interCharAttr, typeForm);
	}

	/**
	 * Converts a text attribute to its "type form" equivalent. Note that type form
	 * values should be a power of two, since they can be superimposed to create
	 * composite type forms. For example: "italic"=>1, "underline"=>2 and "bold"=>4
	 * @param attr the text attribute
	 * @param map the text attribute name to type form value map
	 * @return returns an array with the corresponding values
	 */
	static short[] toTypeForm(TextAttribute attr, Map<String, Integer> map) {
		short[] ret = new short[attr.getWidth()];
		short typeForm = 0;
		if (attr.getDictionaryIdentifier()!=null) {
			Short v = map.get(attr.getDictionaryIdentifier()).shortValue();
			if (v!=null) {
				typeForm = v;
			}
		}

		if (attr.hasChildren()) {
			int offset = 0;
			for (TextAttribute t : attr) {
				short[] v = toTypeForm(t, map);
				for (int i=0; i<v.length; i++) {
					ret[i+offset] = (short)(typeForm | v[i]);
				}
				offset += t.getWidth();
			}
		} else {
			for (int i=0; i<ret.length; i++) {
				ret[i] = typeForm;
			}
		}
		return ret;
	}

	private static String toBrailleFilterString(String input, TranslationResult res) {
		return toBrailleFilterString(input, res.getBraille(), res.getCharacterAttributes(), res.getInterCharacterAttributes());
	}

	/**
	 * Modifies a string from Liblouis into a string that is compatible with {@link BrailleFilter}
	 * by adding hyphenation characters (soft hyphen and zero width space). 
	 * @param str the Liblouis string
	 * @param interCharAttr the inter char attributes.
	 * @return a string
	 */
	static String toBrailleFilterString(String input, String str, int[] charAtts, int[] interCharAttr) {
		StringBuilder sb = new StringBuilder();
		int[] inputCodePoints = input.codePoints().toArray();
		int[] codePoints = str.codePoints().toArray();
		int prvInputIndex = -1;
		int inputIndex, inputCP;
		for (int outputIndex=0; outputIndex<codePoints.length; outputIndex++) {
			inputIndex = charAtts[outputIndex];
			inputCP = inputCodePoints[inputIndex];
			// The following is needed because some tables in Liblouis translate spaces into braille cells, e.g. Danish.
			// The BrailleFilter contract requires spaces to be preserved.
			if (Character.isWhitespace(inputCP)) {
				// If the input index for the output index is the same as the previous
				// input index, then this output character belongs to the same input character.
				// If so, the character has already been processed, and should not be added to the
				// output again.
				if (prvInputIndex!=inputIndex) {
					sb.appendCodePoint(' ');
				}
				prvInputIndex=inputIndex;
			} else {
				prvInputIndex = -1;
				sb.appendCodePoint(codePoints[outputIndex]);
			}
			if (outputIndex<interCharAttr.length) {
				switch (interCharAttr[outputIndex]) {
					case LIBLOUIS_NO_BREAKPOINT: break;
					case LIBLOUIS_SOFT_HYPEN: sb.append('\u00ad'); break;
					case LIBLOUIS_ZERO_WIDTH_SPACE: sb.append('\u200b'); break;
					default:
				}
			}
		}
		return sb.toString();
	}

}
