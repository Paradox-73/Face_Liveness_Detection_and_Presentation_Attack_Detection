/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Maps rows of one mosip-data table to the JSON objects the desktop client's {@code MapperUtils.mapJSONObjectToEntity}
 * turns into one entity class. The client copies JSON values into entity fields by field name without conversion,
 * so each value must already have the Java type of the field: strings for String fields, JSON integers for
 * Integer/int fields, JSON booleans for Boolean fields and {@code HH:mm:ss} strings for {@code java.sql.Time} fields.
 */
public final class EntitySpec {

	/** Value kinds, matching the entity field types. */
	public enum Kind {
		/** String field. */
		STRING,
		/** Integer or int field. */
		INT,
		/** Boolean field. */
		BOOL,
		/** java.sql.Time field, sent as {@code HH:mm:ss}. */
		TIME
	}

	private final String entityName;
	private final String table;
	private final List<String> keyFields;
	private final List<Column> columns = new ArrayList<>();
	private Predicate<Map<String, Object>> rowFilter = row -> true;

	private EntitySpec(String entityName, String table, String... keyFields) {
		this.entityName = entityName;
		this.table = table;
		this.keyFields = Arrays.asList(keyFields);
	}

	/**
	 * Starts a spec.
	 *
	 * @param entityName entity name as sent in {@code dataToSync[].entityName}
	 * @param table      mosip-data table
	 * @param keyFields  entity primary key fields, used to drop duplicate rows (other languages of the same key)
	 * @return new spec
	 */
	public static EntitySpec of(String entityName, String table, String... keyFields) {
		return new EntitySpec(entityName, table, keyFields);
	}

	/**
	 * Adds a field copied from a column.
	 *
	 * @param field  entity field name
	 * @param column table column name
	 * @param kind   value kind
	 * @return this spec
	 */
	public EntitySpec field(String field, String column, Kind kind) {
		columns.add(new Column(field, column, kind));
		return this;
	}

	/**
	 * Adds a string field copied from the column of the same name in snake case.
	 *
	 * @param fields entity field names whose columns are the snake-case form
	 * @return this spec
	 */
	public EntitySpec strings(String... fields) {
		for (String field : fields) {
			columns.add(new Column(field, snake(field), Kind.STRING));
		}
		return this;
	}

	/**
	 * Keeps only rows accepted by the filter.
	 *
	 * @param filter row filter
	 * @return this spec
	 */
	public EntitySpec where(Predicate<Map<String, Object>> filter) {
		this.rowFilter = filter;
		return this;
	}

	/**
	 * Returns the entity name.
	 *
	 * @return entity name
	 */
	public String getEntityName() {
		return entityName;
	}

	/**
	 * Returns the mosip-data table.
	 *
	 * @return table name
	 */
	public String getTable() {
		return table;
	}

	/**
	 * Converts table rows to entity JSON objects. Every object also gets {@code isDeleted=false}; the client ignores
	 * keys that its entity class does not have.
	 *
	 * @param rows        table rows
	 * @param primaryLang language whose rows win when several languages share one primary key
	 * @return entity objects
	 */
	public List<Map<String, Object>> convert(List<Map<String, Object>> rows, String primaryLang) {
		Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
		List<Map<String, Object>> ordered = new ArrayList<>(rows);
		ordered.sort((a, b) -> Boolean.compare(!primaryLang.equals(a.get("lang_code")), !primaryLang.equals(b.get("lang_code"))));
		for (Map<String, Object> row : ordered) {
			if (!rowFilter.test(row)) {
				continue;
			}
			Map<String, Object> entity = new LinkedHashMap<>();
			for (Column column : columns) {
				entity.put(column.field, convertValue(row.get(column.column), column.kind));
			}
			entity.put("isDeleted", Boolean.FALSE);
			StringBuilder key = new StringBuilder();
			for (String keyField : keyFields) {
				key.append(entity.get(keyField)).append('|');
			}
			byKey.putIfAbsent(key.toString(), entity);
		}
		return new ArrayList<>(byKey.values());
	}

	/**
	 * Converts one cell value to the given kind.
	 *
	 * @param value cell value
	 * @param kind  target kind
	 * @return converted value, or null
	 */
	static Object convertValue(Object value, Kind kind) {
		if (value == null) {
			return null;
		}
		switch (kind) {
		case INT:
			return value instanceof Number ? ((Number) value).intValue() : Integer.valueOf(value.toString().trim());
		case BOOL:
			return value instanceof Boolean ? value : Boolean.valueOf("true".equalsIgnoreCase(value.toString().trim()));
		case TIME:
			return normaliseTime(value.toString());
		default:
			return stringValue(value);
		}
	}

	private static String stringValue(Object value) {
		if (value instanceof Double || value instanceof Float) {
			double number = ((Number) value).doubleValue();
			return number == Math.rint(number) && !Double.isInfinite(number) ? String.valueOf((long) number)
					: String.valueOf(value);
		}
		return value.toString();
	}

	private static String normaliseTime(String text) {
		String[] parts = text.trim().split(":");
		if (parts.length != 3) {
			return text;
		}
		return String.format("%02d:%02d:%02d", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
				Integer.parseInt(parts[2]));
	}

	private static String snake(String camel) {
		return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
	}

	private static final class Column {
		private final String field;
		private final String column;
		private final Kind kind;

		private Column(String field, String column, Kind kind) {
			this.field = field;
			this.column = column;
			this.kind = kind;
		}
	}
}
