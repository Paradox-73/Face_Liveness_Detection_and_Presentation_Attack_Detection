package io.mosip.mocksync.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds the {@code response} of {@code GET /v1/syncdata/latestidschema}: the published identity schema from
 * mosip-data {@code identity_schema} plus every published {@code registration-client} UI spec from {@code ui_spec},
 * keyed by its type ({@code schema}, {@code screens}, {@code settings}, {@code newProcess}, {@code updateProcess},
 * {@code lostProcess}, {@code bioCorrectionProcess}), which is the shape syncdata 1.2.0.1 returns.
 * The desktop client stores {@code schemaJson}, {@code settings} and every key ending in {@code process} as a process spec.
 */
@Component
public class SchemaService {

	private static final String DOMAIN = "registration-client";
	private static final String EFFECTIVE_FROM = "2021-01-01T00:00:00.000";

	private final ReferenceData data;
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Creates the service.
	 *
	 * @param data reference data
	 */
	public SchemaService(ReferenceData data) {
		this.data = data;
	}

	/**
	 * Returns the latest schema response.
	 *
	 * @return response map
	 */
	public Map<String, Object> latestSchema() {
		Map<String, Object> schemaRow = data.table("identity_schema").stream()
				.filter(row -> Boolean.TRUE.equals(row.get("is_active")) || row.get("is_active") == null)
				.findFirst().orElseThrow(() -> new IllegalStateException("identity_schema snapshot is empty"));

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("id", String.valueOf(schemaRow.get("id")));
		response.put("idVersion", toDouble(schemaRow.get("id_version")));
		response.put("title", schemaRow.get("title"));
		response.put("description", schemaRow.get("description"));
		response.put("schemaJson", schemaRow.get("schema_json"));
		response.put("effectiveFrom", EFFECTIVE_FROM);
		response.put("updatedOn", EFFECTIVE_FROM);

		List<Map<String, Object>> specs = data.table("ui_spec");
		for (Map<String, Object> spec : specs) {
			if (!DOMAIN.equals(spec.get("domain")) || "UNPUBLISHED".equals(spec.get("status_code"))) {
				continue;
			}
			response.put(String.valueOf(spec.get("type")), parse(String.valueOf(spec.get("json_spec"))));
		}
		return response;
	}

	private Object parse(String json) {
		try {
			return mapper.readValue(json, Object.class);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Invalid UI spec JSON in the ui_spec snapshot", e);
		}
	}

	private static double toDouble(Object value) {
		return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
	}
}
