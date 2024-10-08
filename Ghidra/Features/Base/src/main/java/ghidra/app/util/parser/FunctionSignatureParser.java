/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.parser;

import java.util.*;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import ghidra.app.services.DataTypeQueryService;
import ghidra.app.util.cparser.C.ParseException;
import ghidra.program.database.data.DataTypeUtilities;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.FunctionSignature;
import ghidra.util.data.DataTypeParser;
import ghidra.util.data.DataTypeParser.AllowedDataTypes;
import ghidra.util.exception.CancelledException;

/**
 * Class for parsing function signatures. This class attempts to be much more
 * flexible than a full parser that requires correct C or C++ syntax. To achieve
 * this, it scans the original function signature (if present) for names that
 * would cause parse problems (parens, brackets, asterisk, commas, and spaces). 
 * If it finds any problem names, it looks for those strings in the text to be 
 * parsed and if it finds them, it replaces them with substitutes that parse 
 * easily. Then, after parsing, those replacement strings are then restored to 
 * their original values.
 * <P>
 * Some examples of valid c++ that would fail due to the current limitations:
 * <P>
 * {@literal void foo(myclass<int, float> x) - fails due to comma in x's data type name}
 * int operator()(int x) - fails due to parens in function name unsigned int
 * bar(float y) - fails due to space in return type name
 * <P>
 * Note: you can edit signatures that already have these features as long as
 * your modifications don't affect the pieces containing parens, commas or
 * spaces in their name.
 */
public class FunctionSignatureParser {
	private static final String REPLACEMENT_DT_NAME = "__REPLACE_DT_NAME__";
	private static final String REPLACE_NAME = "__REPLACE_NAME__";
	private DataTypeParser dataTypeParser;
	private Map<String, DataType> dtMap = new HashMap<>();

	private Map<String, String> nameMap = new HashMap<>();
	private DataTypeManager destDataTypeManager;
	private ParserDataTypeManagerService dtmService;
	private boolean cacheChooser = false;
	private CategoryPath defaultCategory = null;

	/**
	 * Constructs a SignatureParser for a program.  The destDataTypeManager and/or
	 * service must be specified.
	 * 
	 * @param destDataTypeManager the destination datatype maanger.
	 * @param service the DataTypeManagerService to use for resolving datatypes that
	 *                can't be found in the given program. Can be null to utilize
	 *                program based types only.
	 */
	public FunctionSignatureParser(DataTypeManager destDataTypeManager,
			DataTypeQueryService service) {
		this.destDataTypeManager = destDataTypeManager;
		if (destDataTypeManager == null && service == null) {
			throw new IllegalArgumentException(
				"Destination DataTypeManager or DataTypeManagerService provider required");
		}
		if (service != null) {
			dtmService = new ParserDataTypeManagerService(service);
		}
		dataTypeParser = new DataTypeParser(destDataTypeManager, destDataTypeManager, dtmService,
			AllowedDataTypes.FIXED_LENGTH);
	}

	/**
	 * Constructs a SignatureParser for a program.  The destDataTypeManager and/or
	 * service must be specified.
	 * 
	 * @param destDataTypeManager the destination datatype maanger.
	 * @param service the DataTypeManagerService to use for resolving datatypes that
	 *                can't be found in the given program. Can be null to utilize
	 *                program based types only.
	 * @param defaultCategory the default category to check first 
	 * @param cacheChooser whether to cache the chooser selections across the 
	 *                signature parser instead of just a single signature. 
	 *                This is useful for bulk signature processing.
	 */
	public FunctionSignatureParser(DataTypeManager destDataTypeManager,
			DataTypeQueryService service, CategoryPath defaultCategory, boolean cacheChooser) {
		this(destDataTypeManager, service);
		this.cacheChooser = cacheChooser;
		this.defaultCategory = defaultCategory;				
	}
	/**
	 * Parse the given function signature text into a FunctionDefinitionDataType.
	 *
	 * @param originalSignature the function signature before editing. This may be
	 *                          null if the user is entering a new signature instead
	 *                          of editing an existing one.
	 * @param signatureText     the text to be parsed into a function signature.
	 * @return the FunctionDefinitionDataType resulting from parsing.
	 * @throws ParseException if the text could not be parsed.
	 * @throws CancelledException if parse cancelled by user
	 */
	public FunctionDefinitionDataType parse(FunctionSignature originalSignature,
			String signatureText) throws ParseException, CancelledException {
		if (!cacheChooser){
			dtMap.clear();
			nameMap.clear();
		}
		if (dtmService != null) {
			dtmService.clearCache(); // clear datatype selection cache
		}

		if (originalSignature != null) {
			initDataTypeMap(originalSignature);
			signatureText = cleanUpSignatureText(signatureText, originalSignature);
		}

		String functionName = extractFunctionName(signatureText);
		FunctionDefinitionDataType function =
			new FunctionDefinitionDataType(functionName, destDataTypeManager);

		function.setReturnType(extractReturnType(signatureText));
		function.setArguments(extractArguments(signatureText));
		function.setVarArgs(hasVarArgs(signatureText));

		return function;
	}

	private void initDataTypeMap(FunctionSignature signature) {
		cacheDataType(signature.getReturnType());
		for (ParameterDefinition p : signature.getArguments()) {
			cacheDataType(p.getDataType());
		}
	}

	private void cacheDataType(DataType dataType) {
		if (dataType == null || (dataType instanceof Dynamic) ||
			(dataType instanceof FactoryDataType)) {
			return;
		}
		DataType baseType = null;
		if (dataType instanceof Pointer) {
			baseType = ((Pointer) dataType).getDataType();
		}
		else if (dataType instanceof Array) {
			baseType = ((Array) dataType).getDataType();
		}
		else if (dataType instanceof TypeDef) {
			baseType = ((TypeDef) dataType).getDataType();
		}
		dtMap.put(dataType.getName(), dataType);
		cacheDataType(baseType);
	}
	
	private void cacheDataType(DataType dataType, String dataTypeString) {
		dtMap.put(dataTypeString, dataType);
		cacheDataType(dataType);
	}

	private boolean hasVarArgs(String newSignatureText) {
		int startIndex = newSignatureText.lastIndexOf(',');
		int endIndex = newSignatureText.indexOf(')');
		if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
			return false;
		}
		String lastArg = newSignatureText.substring(startIndex + 1, endIndex).trim();
		return "...".equals(lastArg);
	}

	private ParameterDefinition[] extractArguments(String newSignatureText)
			throws ParseException, CancelledException {
		int startIndex = newSignatureText.indexOf('(');
		int endIndex = newSignatureText.indexOf(')');
		if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
			throw new ParseException("Can't parse function arguments");
		}
		String trailingText = newSignatureText.substring(endIndex + 1);
		if (trailingText.trim().length() > 0) {
			throw new ParseException(
				"Unexpected trailing text at end of function: " + trailingText);
		}

		String argString = newSignatureText.substring(startIndex + 1, endIndex).trim();
		if (argString.length() == 0) {
			return new ParameterDefinition[0];
		}
		if ("void".equalsIgnoreCase(argString)) {
			return new ParameterDefinition[0];
		}

		List<ParameterDefinition> parameterList = new ArrayList<>();
	    List<String> args = parseArguments(argString);

		for (String arg : args) {
			addParameter(parameterList, arg.trim());
		}
		return parameterList.toArray(new ParameterDefinition[parameterList.size()]);
	}

	private List<String> parseArguments(String argString) throws ParseException {
	    List<String> arguments = new ArrayList<>();
	    StringBuilder currentArg = new StringBuilder();
	    int angleBracketsLevel = 0;
	    boolean inQuotes = false;

	    for (int i = 0; i < argString.length(); i++) {
	        char c = argString.charAt(i);

	        if (c == '<') {
	            angleBracketsLevel++;
	        } else if (c == '>') {
	            angleBracketsLevel--;
	        } else if (c == ',' && angleBracketsLevel == 0 && !inQuotes) {
	            // End of an argument
	            arguments.add(currentArg.toString().trim());
	            currentArg.setLength(0);
	            continue;
	        } else if (c == '"') {
	            inQuotes = !inQuotes; // Toggle quotes state
	        }

	        currentArg.append(c);
	    }

	    // Add the last argument
	    if (currentArg.length() > 0) {
	        arguments.add(currentArg.toString().trim());
	    }

	    return arguments;
	}

	private void addParameter(List<ParameterDefinition> parameterList, String arg)
			throws ParseException, CancelledException {
		if ("...".equals(arg)) {
			return;
		}
		if (arg.length() == 0) {
			throw new ParseException("Missing parameter");
		}

		// Attempt to resolve parameter assuming only a datatype is specified
		DataType dt = resolveDataType(arg);
		if (dt != null) {
			parameterList.add(new ParameterDefinitionImpl(null, dt, null));
			return;
		}

		// attempt to separate trailing parameter name from datatype and reparse
		int spaceIndex = arg.lastIndexOf(' ');
		if (spaceIndex < 0) {
			throw new ParseException("Can't resolve datatype: " + arg);
		}
		int starIndex = arg.lastIndexOf('*');
		int nameIndex = Math.max(spaceIndex, starIndex) + 1;

		String name = resolveName(arg.substring(nameIndex).trim());
		String dtName = arg.substring(0, nameIndex).trim();
		dt = resolveDataType(dtName);
		if (dt == null) {
			throw new ParseException("Can't resolve datatype: " + dtName);
		}
		parameterList.add(new ParameterDefinitionImpl(name, dt, null));
	}

	String cleanUpSignatureText(String text, FunctionSignature signature) {
		DataType returnType = signature.getReturnType();
		text = replaceDataTypeIfNeeded(text, returnType, REPLACEMENT_DT_NAME);
		text = replaceNameIfNeeded(text, signature.getName(), REPLACE_NAME);

		ParameterDefinition[] arguments = signature.getArguments();
		for (ParameterDefinition argument : arguments) {
			text = replaceDataTypeIfNeeded(text, argument.getDataType(),
				REPLACEMENT_DT_NAME + argument.getOrdinal());
			text =
				replaceNameIfNeeded(text, argument.getName(), REPLACE_NAME + argument.getOrdinal());
		}
		return text;
	}

	private String replaceDataTypeIfNeeded(String text, DataType dataType, String replacementName) {
		String datatypeName = dataType.getName();
		if (canParseType(datatypeName)) {
			return text;
		}

		dtMap.put(replacementName, dataType);

		return substitute(text, datatypeName, replacementName);
	}

	private String replaceNameIfNeeded(String text, String name, String replacementName) {
		if (canParseName(name)) {
			return text;
		}
		nameMap.put(replacementName, name);
		return substitute(text, name, replacementName);
	}

	DataType extractReturnType(String signatureText) throws ParseException, CancelledException {
		String returnTypeName = "";
		int parenIndex = signatureText.indexOf('(');
		if (parenIndex < 0) {
			throw new ParseException("Can't find return type");
		}
		// Extract the part of the signature before the parentheses
		String beforeParen = signatureText.substring(0, parenIndex).trim();
		String[] split = customSplit(beforeParen, "\\s+"); // Split by whitespace

		int keywordIndex = findKeywordIndex(split);

		// If keyword is not found, handle as a single word function name
		if (keywordIndex == -1) {
			if (split.length < 2) {
				throw new ParseException("Can't find return type");
			}
			returnTypeName = split[0];
		} else if (keywordIndex > 1 && keywordIndex < split.length){
			// Otherwise, we have keyword, join all parts from start to before the keyword
			returnTypeName = String.join(" ", Arrays.copyOfRange(split, 0, keywordIndex - 1));
		}
		if (returnTypeName.isEmpty())
			returnTypeName = "void";
		DataType dt = resolveDataType(returnTypeName);
		if (dt == null) {
			throw new ParseException("Can't resolve return type: " + returnTypeName);
		}
		return dt;
	}

	private DataType resolveDataType(String dataTypeName) throws CancelledException {
		if (dtMap.containsKey(dataTypeName)) {
			return dtMap.get(dataTypeName);
		}

		DataType dataType = null;
		try {
			if (this.defaultCategory != null) {
				dataType = dataTypeParser.parse(dataTypeName, this.defaultCategory);
			}
			if (dataType != null && cacheChooser) {
				cacheDataType(dataType, dataTypeName);
			}
		} catch (InvalidDataTypeException e) {
			try {
				// try again without category
				dataType = dataTypeParser.parse(dataTypeName);
				if (dataType != null && cacheChooser) {
					cacheDataType(dataType, dataTypeName);
				}
			} catch (InvalidDataTypeException e2) {
				// ignore
			}
		}
		return dataType;
	}
	
	String extractFunctionName(String signatureText) throws ParseException {
		int parenIndex = signatureText.indexOf('(');
		if (parenIndex < 0) {
			throw new ParseException("Can't find function name");
		}
		// Extract the part of the signature before the parentheses
		String beforeParen = signatureText.substring(0, parenIndex).trim();
		String[] split = customSplit(beforeParen, "\\s+"); // Split by whitespace

		int keywordIndex = findKeywordIndex(split);

		// If keyword is not found, handle as a single word function name
	    if (keywordIndex == -1) {
	        if (split.length < 2) {
	            throw new ParseException("Can't find function name");
	        }
	        return resolveName(split[split.length - 1]);
	    }

	    // Otherwise, we have operator, Join all parts from "operator" to the end
	    String name = String.join(" ", Arrays.copyOfRange(split, keywordIndex, split.length));
	    return resolveName(name);
	}

	private String resolveName(String name) throws ParseException {
		if (nameMap.containsKey(name)) {
			return nameMap.get(name);
		}
		// Use findKeywordIndex to identify where the relevant function name starts
		String[] split = customSplit(name, "\\s+"); // Split by whitespace

		int keywordIndex = findKeywordIndex(split);

		String resolvedName = "";
		if (keywordIndex != -1) {
			// Filter out all keywords except "operator"
			List<String> filteredParts = new ArrayList<>();
			for (int i = keywordIndex; i < split.length; i++) {
				if ("operator".equals(split[i]) || !isKeyword(split[i])) {
					filteredParts.add(split[i]);
				}
			}
			resolvedName = String.join(" ", filteredParts).trim();
		}else {
			resolvedName = String.join(" ", split).trim();
		}
		if (!canParseName(resolvedName)) {
			throw new ParseException("Can't parse name: " + name);
		}
		nameMap.put(name, resolvedName);
		return resolvedName;
	}

	String substitute(String text, String searchString, String replacementString) {
		return text.replaceFirst(Pattern.quote(searchString), replacementString);
	}

	/**
	 * Custom split method to handle complex splitting scenarios, including nested templates and quotes.
	 * This method uses regex to split the input string based on the provided delimiter regex.
	 * 
	 * @param input the string to split
	 * @param regex the regular expression used to split the string
	 * @return an array of strings split by the specified regex
	 */
	private String[] customSplit(String input, String regex) throws ParseException {
	    List<String> result = new ArrayList<>();
	    StringBuilder currentPart = new StringBuilder();
	    int angleBracketsLevel = 0;
	    boolean inQuotes = false;

	    Pattern delimiterPattern = Pattern.compile(regex);

	    for (int i = 0; i < input.length(); i++) {
	        char c = input.charAt(i);

	        if (c == '<') {
	            angleBracketsLevel++;
	        } else if (c == '>') {
	            angleBracketsLevel--;
	        } else if (c == '"') {
	            inQuotes = !inQuotes; // Toggle quotes state
	        } else if (delimiterPattern.matcher(String.valueOf(c)).matches() && angleBracketsLevel == 0 && !inQuotes) {
	            if (currentPart.length() > 0) {
	                result.add(currentPart.toString().trim());
	                currentPart.setLength(0);
	            }
	            continue;
	        }

	        currentPart.append(c);
	    }

	    // Add the last part
	    if (currentPart.length() > 0) {
	        result.add(currentPart.toString().trim());
	    }

	    return result.toArray(new String[0]);
	}
	
	private int findKeywordIndex(String[] split) {
    for (int i = split.length - 1; i >= 0; i--) {
        if (isKeyword(split[i])) {
            return i;
        }
    }
    return -1;
}
	
	private boolean isKeyword(String word) {
	    Set<String> keywords = Set.of("const", "__cdecl", "__clrcall", "__stdcall", "__fastcall",
	                                  "__thiscall", "__vectorcall", "operator");
	    return keywords.contains(word);
	}
	
	private boolean canParseName(String text) {
		return (!StringUtils.containsAny(text, "()*[]") || text.startsWith("operator") && !StringUtils.containsAny(text, "()"));
	}

	private boolean canParseType(String text) {
		return !StringUtils.containsAny(text, "()<>,");
	}

	/**
	 * Provides a simple caching datatype manager service wrapper.<br>
	 * Implementation intended for use with {@link FunctionSignatureParser}
	 * and underlying {@link DataTypeParser} and {@link DataTypeUtilities} classes.  
	 */
	private static class ParserDataTypeManagerService implements DataTypeQueryService {

		private Map<String, DataType> dtCache = new HashMap<>();

		private final DataTypeQueryService service;

		/**
		 * Construct caching datatype manager service.
		 * @param service actual datatype manager service which may prompt
		 * user to make a datatype selection.  It is this impementation's 
		 * purpose to cache such a choice for {@link #getDataType(String)} to
		 * avoid repeated selections of the same choice if type is reused
		 * within a function signature.
		 */
		ParserDataTypeManagerService(DataTypeQueryService service) {
			this.service = service;
		}

		void clearCache() {
			dtCache.clear();
		}

		@Override
		public List<DataType> getSortedDataTypeList() {
			return service.getSortedDataTypeList();
		}

		@Override
		public DataType getDataType(String filterText) {
			DataType dt = dtCache.get(filterText);
			if (dt == null) {
				dt = service.getDataType(filterText);
				if (dt != null) {
					dtCache.put(filterText, dt);
				}
			}
			return dt;
		}

	}
}
