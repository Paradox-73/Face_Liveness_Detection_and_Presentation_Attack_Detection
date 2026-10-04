/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.data;

import static io.mosip.mocksync.data.EntitySpec.Kind.BOOL;
import static io.mosip.mocksync.data.EntitySpec.Kind.INT;
import static io.mosip.mocksync.data.EntitySpec.Kind.STRING;
import static io.mosip.mocksync.data.EntitySpec.Kind.TIME;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.machine.Machine;

/**
 * Builds the plain (not yet encrypted) {@code dataToSync} entries of {@code GET /v1/syncdata/v2/clientsettings}.
 * <p>
 * Entity names and field names follow the desktop client's {@code ClientSettingSyncHelper} and its entity classes
 * ({@code io.mosip.registration.entity.*}). The client stores every entity in one pass and fails the whole sync
 * when one entity cannot be mapped, so each field carries the Java type of the entity field.
 */
@Component
public class ClientSettingsBuilder {

	/** Entity type of plain entity lists. */
	public static final String STRUCTURED = "structured";
	/** Entity type of dynamic field lists. */
	public static final String DYNAMIC = "dynamic";
	/** Entity type of downloadable scripts. */
	public static final String SCRIPT = "script";

	/** Script files the client downloads after the entity sync. */
	public static final List<String> SCRIPTS = List.of("applicanttype.mvel");

	private static final Logger LOGGER = LoggerFactory.getLogger(ClientSettingsBuilder.class);
	private static final String MACHINE_TYPE = "DKS";
	private static final String MACHINE_SPEC = "MOCKSYNC-SPEC-1";

	/**
	 * Many-to-one links of the client entities, as {child entity, child fields, parent entity, parent fields}.
	 * Hibernate loads the parent eagerly when the child is saved, so a child row without its parent fails the whole
	 * sync; such rows are dropped (mosip-data 1.2.0.1 has French and Arabic biometric attributes but English-only
	 * biometric types).
	 */
	private static final String[][] REFERENCES = {
			{ "BiometricAttribute", "biometricTypeCode,langCode", "BiometricType", "code,langCode" },
			{ "ApplicantValidDocument", "docCatCode,langCode", "DocumentCategory", "code,langCode" },
			{ "ApplicantValidDocument", "docTypeCode,langCode", "DocumentType", "code,langCode" },
			{ "ReasonList", "rsnCatCode,langCode", "ReasonCategory", "code,langCode" },
			{ "MachineSpecification", "machineTypeCode", "MachineType", "code" } };

	/**
	 * The liveness keys a client machine may override locally, the same list as the core's allow-list
	 * ({@code LivenessConfigKeys.isLocalOverrideAllowed}): the PAD thresholds, the challenge and stream timeouts,
	 * attempt limits (global and per workflow) and the diagnostic switch. {@code active.challenge_types},
	 * {@code passive.timeout_ms} and {@code binding.min_similarity} are server-only: a local value could shrink the
	 * challenge pool, shorten the passive check or accept a different face.
	 */
	private static final Pattern LOCALLY_EDITABLE = Pattern.compile("mosip\\.registration\\.liveness\\.(?:"
			+ "(?:[a-z_]+\\.)?(?:passive\\.threshold|max_retries)|pad\\.reject_threshold"
			+ "|active\\.challenge_timeout_ms|stream\\.timeout_ms|diagnostic\\.enable)");

	private final ReferenceData data;
	private final ConfigService config;
	private final MockSyncProperties properties;
	private final ObjectMapper mapper = new ObjectMapper();
	private final List<EntitySpec> specs;

	/**
	 * Creates the builder.
	 *
	 * @param data       reference data
	 * @param config     config service (for the locally editable keys)
	 * @param properties MockSync settings
	 */
	public ClientSettingsBuilder(ReferenceData data, ConfigService config, MockSyncProperties properties) {
		this.data = data;
		this.config = config;
		this.properties = properties;
		this.specs = entitySpecs(properties.getCenterId());
	}

	/**
	 * One {@code dataToSync} entry before encryption.
	 */
	public static final class Entry {
		private final String entityName;
		private final String entityType;
		private final String json;

		Entry(String entityName, String entityType, String json) {
			this.entityName = entityName;
			this.entityType = entityType;
			this.json = json;
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
		 * Returns the entity type ({@code structured}, {@code dynamic} or {@code script}).
		 *
		 * @return entity type
		 */
		public String getEntityType() {
			return entityType;
		}

		/**
		 * Returns the plain JSON that is encrypted into {@code data}.
		 *
		 * @return JSON text
		 */
		public String getJson() {
			return json;
		}
	}

	/**
	 * Builds all entries for one machine.
	 *
	 * @param machine    requesting machine
	 * @param scriptBase base URL for script downloads, e.g. {@code https://localhost:8443}
	 * @return entries in the order the real syncdata service sends them
	 */
	public List<Entry> build(Machine machine, String scriptBase) {
		String lang = properties.getPrimaryLang();
		Map<String, List<Map<String, Object>>> byEntity = new LinkedHashMap<>();
		for (EntitySpec spec : specs) {
			List<Map<String, Object>> rows = spec.convert(data.table(spec.getTable()), lang);
			if ("AppAuthenticationMethod".equals(spec.getEntityName())) {
				applyAuthMethodRules(rows);
			}
			if ("MachineType".equals(spec.getEntityName())) {
				rows.add(machineType(lang));
			} else if ("MachineSpecification".equals(spec.getEntityName())) {
				rows.add(machineSpecification(lang));
			}
			byEntity.put(spec.getEntityName(), rows);
		}
		for (String[] reference : REFERENCES) {
			keepReferenced(byEntity, reference);
		}
		List<Entry> entries = new ArrayList<>();
		byEntity.forEach((entityName, rows) -> addStructured(entries, entityName, rows));
		addStructured(entries, "Machine", List.of(machineRow(machine, lang)));
		addStructured(entries, "PermittedLocalConfig", permittedLocalConfig());
		entries.addAll(dynamicFields());
		for (String script : SCRIPTS) {
			Map<String, Object> download = new LinkedHashMap<>();
			download.put("url", scriptBase + "/v1/syncdata/scripts/" + script);
			download.put("headers", "");
			download.put("auth-required", Boolean.TRUE);
			download.put("auth-token", "Authorization:OAUTH");
			download.put("encrypted", Boolean.FALSE);
			entries.add(new Entry(script, SCRIPT, toJson(download)));
		}
		return entries;
	}

	/**
	 * Returns the {@code PermittedLocalConfig} rows: liveness keys an operator may change in Settings.
	 * The master switch, policies, engine and model version are never locally editable.
	 *
	 * @return entity objects
	 */
	public List<Map<String, Object>> permittedLocalConfig() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (String key : config.registrationConfiguration().keySet()) {
			if (!key.startsWith("mosip.registration.liveness.") || key.contains(".policy.")
					|| key.endsWith(".enable") && !key.endsWith("diagnostic.enable") || key.endsWith(".engine")
					|| key.endsWith("model.min_version")) {
				continue;
			}
			if (LOCALLY_EDITABLE.matcher(key).matches()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("code", key);
				row.put("name", key);
				row.put("type", "CONFIGURATION");
				row.put("isActive", Boolean.TRUE);
				row.put("isDeleted", Boolean.FALSE);
				rows.add(row);
			}
		}
		return rows;
	}

	private static void keepReferenced(Map<String, List<Map<String, Object>>> byEntity, String[] reference) {
		List<Map<String, Object>> children = byEntity.get(reference[0]);
		List<Map<String, Object>> parents = byEntity.get(reference[2]);
		if (children == null || parents == null) {
			return;
		}
		String[] childFields = reference[1].split(",");
		String[] parentFields = reference[3].split(",");
		Set<String> parentKeys = new java.util.HashSet<>();
		parents.forEach(parent -> parentKeys.add(key(parent, parentFields)));
		int before = children.size();
		children.removeIf(child -> !parentKeys.contains(key(child, childFields)));
		if (children.size() < before) {
			LOGGER.debug("Dropped {} {} row(s) without a {} row", before - children.size(), reference[0], reference[2]);
		}
	}

	private static String key(Map<String, Object> row, String[] fields) {
		StringBuilder key = new StringBuilder();
		for (String field : fields) {
			key.append(row.get(field)).append('|');
		}
		return key.toString();
	}

	private void applyAuthMethodRules(List<Map<String, Object>> rows) {
		for (Map<String, Object> row : rows) {
			String process = String.valueOf(row.get("processId"));
			String method = String.valueOf(row.get("authMethodCode"));
			if ("FACE".equals(method)) {
				row.put("isActive", properties.getFaceAuthProcesses().contains(process));
			}
		}
	}

	private Map<String, Object> machineRow(Machine machine, String lang) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", machine.getMachineId());
		row.put("langCode", lang);
		row.put("name", machine.getName());
		row.put("serialNum", "MOCKSYNC-" + machine.getMachineId());
		row.put("macAddress", null);
		row.put("ipAddress", null);
		row.put("machineSpecId", MACHINE_SPEC);
		row.put("publicKey", machine.getPublicKey());
		row.put("signPublicKey", machine.getSignPublicKey());
		row.put("keyIndex", machine.getKeyIndex());
		row.put("signKeyIndex", machine.getSignKeyIndex());
		row.put("regCenterId", machine.getCenterId());
		row.put("zoneCode", null);
		row.put("isActive", machine.isActive());
		row.put("isDeleted", Boolean.FALSE);
		return row;
	}

	private static Map<String, Object> machineType(String lang) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("code", MACHINE_TYPE);
		row.put("langCode", lang);
		row.put("name", "Desktop");
		row.put("description", "Registration client machine (MockSync)");
		row.put("isActive", Boolean.TRUE);
		row.put("isDeleted", Boolean.FALSE);
		return row;
	}

	private static Map<String, Object> machineSpecification(String lang) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", MACHINE_SPEC);
		row.put("langCode", lang);
		row.put("name", "Registration client machine");
		row.put("brand", "Generic");
		row.put("model", "Generic");
		row.put("machineTypeCode", MACHINE_TYPE);
		row.put("minDriverversion", "1.0");
		row.put("description", "Registration client machine (MockSync)");
		row.put("isActive", Boolean.TRUE);
		row.put("isDeleted", Boolean.FALSE);
		return row;
	}

	private List<Entry> dynamicFields() {
		Map<String, Map<String, Map<String, Object>>> byNameAndLang = new LinkedHashMap<>();
		for (Map<String, Object> row : data.table("dynamic_field")) {
			String name = String.valueOf(row.get("name"));
			String lang = String.valueOf(row.get("lang_code"));
			Map<String, Object> field = byNameAndLang.computeIfAbsent(name, n -> new LinkedHashMap<>())
					.computeIfAbsent(lang, l -> newDynamicField(row));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> values = (List<Map<String, Object>>) field.get("fieldVal");
			Map<String, Object> value = parseFieldValue(row.get("value_json"));
			if (value != null) {
				value.put("active", !Boolean.FALSE.equals(row.get("is_active")));
				values.add(value);
			}
		}
		List<Entry> entries = new ArrayList<>();
		byNameAndLang.forEach((name, perLang) -> entries.add(new Entry(name, DYNAMIC, toJson(new ArrayList<>(perLang.values())))));
		return entries;
	}

	private static Map<String, Object> newDynamicField(Map<String, Object> row) {
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("id", String.valueOf(row.get("id")));
		field.put("name", row.get("name"));
		field.put("langCode", row.get("lang_code"));
		field.put("dataType", row.get("data_type"));
		field.put("description", row.get("description"));
		field.put("fieldVal", new ArrayList<Map<String, Object>>());
		field.put("isActive", Boolean.TRUE);
		field.put("isDeleted", Boolean.FALSE);
		return field;
	}

	private Map<String, Object> parseFieldValue(Object valueJson) {
		if (valueJson == null) {
			return null;
		}
		try {
			return mapper.readValue(valueJson.toString(), new TypeReference<LinkedHashMap<String, Object>>() {
			});
		} catch (JsonProcessingException e) {
			return null;
		}
	}

	private void addStructured(List<Entry> entries, String entityName, List<Map<String, Object>> rows) {
		if (!rows.isEmpty()) {
			entries.add(new Entry(entityName, STRUCTURED, toJson(rows)));
		}
	}

	private String toJson(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<EntitySpec> entitySpecs(String centerId) {
		List<EntitySpec> list = new ArrayList<>();
		list.add(EntitySpec.of("MachineType", "machine_type", "code").strings("code", "langCode", "name")
				.field("description", "descr", STRING).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("MachineSpecification", "machine_spec", "id").strings("id", "langCode", "name", "brand", "model")
				.field("machineTypeCode", "mtyp_code", STRING).field("minDriverversion", "min_driver_ver", STRING)
				.field("description", "descr", STRING).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("RegistrationCenterType", "reg_center_type", "code", "langCode")
				.strings("code", "langCode", "name", "descr").field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("RegistrationCenter", "registration_center", "id", "langCode")
				.where(row -> centerId.equals(String.valueOf(row.get("id"))))
				.strings("id", "langCode", "name", "latitude", "longitude", "locationCode", "contactPhone", "contactPerson",
						"workingHours", "timeZone")
				.field("centerTypeCode", "cntrtyp_code", STRING).field("addressLine1", "addr_line1", STRING)
				.field("addressLine2", "addr_line2", STRING).field("addressLine3", "addr_line3", STRING)
				.field("numberOfKiosks", "number_of_kiosks", INT).field("perKioskProcessTime", "per_kiosk_process_time", TIME)
				.field("centerStartTime", "center_start_time", TIME).field("centerEndTime", "center_end_time", TIME)
				.field("lunchStartTime", "lunch_start_time", TIME).field("lunchEndTime", "lunch_end_time", TIME)
				.field("holidayLocationCode", "holiday_loc_code", STRING).field("zoneCode", "zone_code", STRING)
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("AppRolePriority", "app_role_priority", "appId", "processId", "roleCode")
				.strings("appId", "processId", "roleCode", "langCode").field("priority", "priority", INT)
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("AppAuthenticationMethod", "app_authentication_method", "appId", "processId", "roleCode",
				"authMethodCode").strings("appId", "processId", "roleCode", "authMethodCode", "langCode")
				.field("methodSequence", "method_seq", INT).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("Template", "template", "id", "langCode").strings("id", "langCode", "name", "model", "moduleId",
				"moduleName", "fileFormatCode").field("fileText", "file_txt", STRING)
				.field("templateTypeCode", "template_typ_code", STRING).field("description", "descr", STRING)
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("DocumentType", "doc_type", "code", "langCode").strings("code", "langCode", "name")
				.field("description", "descr", STRING).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("DocumentCategory", "doc_category", "code", "langCode").strings("code", "langCode", "name")
				.field("description", "descr", STRING).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("ApplicantValidDocument", "applicant_valid_document", "appTypeCode", "docCatCode", "docTypeCode")
				.field("appTypeCode", "apptyp_code", STRING).field("docCatCode", "doccat_code", STRING)
				.field("docTypeCode", "doctyp_code", STRING).strings("langCode").field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("BiometricType", "biometric_type", "code", "langCode").strings("code", "langCode", "name")
				.field("description", "descr", STRING).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("BiometricAttribute", "biometric_attribute", "code", "langCode").strings("code", "langCode", "name")
				.field("description", "descr", STRING).field("biometricTypeCode", "bmtyp_code", STRING)
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("Location", "location", "code", "langCode").strings("code", "langCode", "name", "parentLocCode")
				.field("hierarchyLevel", "hierarchy_level", INT).field("hierarchyName", "hierarchy_level_name", STRING)
				.field("hierarchyLevelName", "hierarchy_level_name", STRING).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("LocationHierarchy", "loc_hierarchy_list", "hierarchyLevel", "langCode")
				.field("hierarchyLevel", "hierarchy_level", INT).strings("hierarchyLevelName", "langCode")
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("BlocklistedWords", "blocklisted_words", "word", "langCode").strings("word", "langCode")
				.field("description", "descr", STRING).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("ProcessList", "process_list", "id", "langCode").strings("id", "langCode", "name", "descr")
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("ScreenDetail", "screen_detail", "id", "langCode").strings("id", "langCode", "appId", "name", "descr")
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("ScreenAuthorization", "screen_authorization", "screenId", "roleCode")
				.strings("screenId", "roleCode", "langCode").field("isPermitted", "is_permitted", BOOL)
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("Language", "language", "code").strings("code", "name", "family", "nativeName")
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("ReasonCategory", "reason_category", "code", "langCode").strings("code", "langCode", "name")
				.field("description", "descr", STRING).field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("ReasonList", "reason_list", "rsnCatCode", "code", "langCode").strings("code", "langCode", "name")
				.field("description", "descr", STRING).field("rsnCatCode", "rsncat_code", STRING)
				.field("isActive", "is_active", BOOL));
		list.add(EntitySpec.of("SyncJobDef", "sync_job_def", "id").strings("id", "langCode", "name", "apiName", "syncFreq",
				"lockDuration").field("parentSyncJobId", "parent_syncjob_id", STRING).field("isActive", "is_active", BOOL));
		return list;
	}
}
