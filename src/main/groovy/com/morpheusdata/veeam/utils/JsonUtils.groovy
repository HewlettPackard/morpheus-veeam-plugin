package com.morpheusdata.veeam.utils

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Helpers for consuming the Veeam Enterprise Manager REST API JSON representation.
 *
 * Veeam serializes the same schema differently depending on the version and the endpoint:
 * <ul>
 *     <li>Veeam 12 and earlier emit PascalCase property names ({@code SessionId}), Veeam 13 emits camelCase ({@code sessionId}).</li>
 *     <li>Collections are returned either as a bare array ({@code links: [..]}) or wrapped in a singular child
 *         element ({@code objectsInJob: [objectInJob: [..]]}).</li>
 * </ul>
 * Responses are normalized to camelCase so the rest of the plugin has a single shape to work against, which also
 * matches the shape previously produced by the deprecated XML API parsing.
 */
class JsonUtils {

	/**
	 * Recursively rewrite every key of a decoded JSON document to camelCase.
	 *
	 * @param value a decoded JSON value, typically a Map or List
	 * @return a copy of the value with camelCase keys
	 */
	static Object normalize(Object value) {
		if(value instanceof Map) {
			def rtn = [:]
			value.each { key, entry ->
				rtn[getCamelKeyName(key.toString())] = normalize(entry)
			}
			return rtn
		}
		if(value instanceof Collection) {
			return value.collect { normalize(it) }
		}
		return value
	}

	/**
	 * Normalize a decoded JSON document to a camelCase Map.
	 *
	 * @param value a decoded JSON value
	 * @return the normalized map, or an empty map when the value is not a map
	 */
	static Map normalizeMap(Object value) {
		def rtn = normalize(value)
		return (rtn instanceof Map) ? rtn : [:]
	}

	/**
	 * Fetch a property from a raw (un-normalized) response, ignoring the PascalCase/camelCase difference between
	 * Veeam versions. Use this when the value is read back out of a document that must be sent to Veeam unmodified.
	 *
	 * @param source the map to read from
	 * @param name the property name in either casing
	 * @return the value or null when not present
	 */
	static Object getValue(Object source, String name) {
		if(!(source instanceof Map) || name == null) {
			return null
		}
		if(source.containsKey(name)) {
			return source[name]
		}
		def match = source.keySet().find { it?.toString()?.equalsIgnoreCase(name) }
		return match != null ? source[match] : null
	}

	/**
	 * Assign a property on a raw (un-normalized) response, reusing the existing key when one is present in either
	 * casing so a document fetched from Veeam can be modified and sent back without altering its schema.
	 *
	 * @param source the map to write to
	 * @param name the property name
	 * @param value the value to assign
	 */
	static void setValue(Object source, String name, Object value) {
		if(!(source instanceof Map) || name == null) {
			return
		}
		def match = source.containsKey(name) ? name : source.keySet().find { it?.toString()?.equalsIgnoreCase(name) }
		source[match != null ? match : name] = value
	}

	/**
	 * Walk a nested path of a normalized document.
	 *
	 * @param source the map to traverse
	 * @param path the sequence of camelCase keys to follow
	 * @return the value at the path or null when any segment is missing
	 */
	static Object get(Object source, String... path) {
		def current = source
		for(String key in path) {
			if(!(current instanceof Map)) {
				return null
			}
			current = current[key]
		}
		return current
	}

	/**
	 * Coerce a value into a List, unwrapping the singular child container Veeam uses for some collections.
	 *
	 * @param value the value to coerce
	 * @param childKey the singular element name used when the collection is wrapped, e.g. {@code link}
	 * @return the value as a list, never null
	 */
	static List toList(Object value, String childKey = null) {
		if(value == null) {
			return []
		}
		if(value instanceof Collection) {
			return value.toList()
		}
		if(childKey && value instanceof Map && value.containsKey(childKey)) {
			return toList(value[childKey])
		}
		return [value]
	}

	/**
	 * Read a collection property, tolerating the bare array, the singular-child-wrapped and the
	 * same-name-wrapped representations. The last shape is used by the query endpoint and the API root, e.g.
	 * {@code entities.backupJobSessions.backupJobSessions} and {@code supportedVersions.supportedVersions}.
	 *
	 * @param source the map to read from
	 * @param key the collection property name, e.g. {@code links}
	 * @param childKey the singular element name used when the collection is wrapped, e.g. {@code link}
	 * @return the collection as a list, never null
	 */
	static List getList(Object source, String key, String childKey = null) {
		if(!(source instanceof Map)) {
			return toList(source, childKey)
		}
		def value = source.containsKey(key) ? source[key] : (childKey ? source[childKey] : null)
		if(value instanceof Map) {
			if(childKey && value.containsKey(childKey)) {
				return toList(value[childKey])
			}
			if(value.containsKey(key)) {
				return toList(value[key])
			}
		}
		return toList(value, childKey)
	}

	/**
	 * Extract a collection from a normalized response document, tolerating the three shapes Veeam uses for
	 * collections: a bare root array, a plural container holding an array, or a plural container holding a
	 * singular child element.
	 *
	 * @param response the normalized response document
	 * @param plural the plural container name, e.g. {@code jobs}
	 * @param singular the singular element name, e.g. {@code job}
	 * @return the collection as a list, never null
	 */
	static List getEntityList(Object response, String plural, String singular) {
		if(response instanceof Collection) {
			return response.toList()
		}
		return getList(response, plural, singular)
	}

	/**
	 * Read the {@code links} collection of a normalized entity.
	 *
	 * @param source the entity to read from
	 * @return the links as a list of maps, never null
	 */
	static List getLinks(Object source) {
		return getList(source, 'links', 'link')
	}

	/**
	 * Find a link of the given type or relation within a normalized entity.
	 *
	 * @param source the entity to read from
	 * @param types the link {@code type} or {@code rel} values to match
	 * @return the matching link map or null
	 */
	static Map findLink(Object source, String... types) {
		def matches = types as List
		return (Map) getLinks(source).find { link ->
			matches.contains(link?.type?.toString()) || matches.contains(link?.rel?.toString())
		}
	}

	/**
	 * Coerce a JSON value to a Long. Veeam 13 returns real JSON numbers where the deprecated XML representation
	 * returned strings, so both need to be handled.
	 *
	 * @param value the value to coerce
	 * @return the value as a Long, or null when it cannot be parsed
	 */
	static Long toLong(Object value) {
		if(value == null) {
			return null
		}
		if(value instanceof Number) {
			return ((Number) value).longValue()
		}
		try {
			return new BigDecimal(value.toString().trim()).longValue()
		} catch(NumberFormatException ignored) {
			return null
		}
	}

	/**
	 * Normalize a Veeam key to camelCase, preserving the acronym handling used for entity identifiers.
	 *
	 * @param key the raw key
	 * @return the camelCase key
	 */
	static String getCamelKeyName(String key) {
		if(key == 'UID')
			return 'uid'
		if(key == 'ID')
			return 'id'
		return lowerCamelCase(key)
	}

	private static String lowerCamelCase(String word) {
		return camelCase(word, false)
	}

	private static String camelCase(String word, boolean uppercaseFirstLetter) {
		if (word == null) return null
		word = word.trim()
		if (word.length() == 0) return ""
		if (uppercaseFirstLetter) {
			// Change the case at the beginning at after each underscore ...
			return replaceAllWithUppercase(word, "(^|_)(.)", 2)
		}
		if (word.length() < 2) return word.toLowerCase()
		return "" + Character.toLowerCase(word.charAt(0)) + camelCase(word, true).substring(1)
	}

	private static String replaceAllWithUppercase(String input, String regex, int groupNumberToUppercase) {
		Pattern underscoreAndDotPattern = Pattern.compile(regex)
		Matcher matcher = underscoreAndDotPattern.matcher(input)
		StringBuffer sb = new StringBuffer()
		while (matcher.find()) {
			matcher.appendReplacement(sb, matcher.group(groupNumberToUppercase).toUpperCase())
		}
		matcher.appendTail(sb)
		return sb.toString()
	}
}
