package io.mosip.mocksync.web;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mocksync.crypto.ServerKeys;

/**
 * Builds MOSIP response envelopes ({@code id, version, responsetime, metadata, response, errors}) and signs them.
 * <p>
 * The desktop client verifies {@code response-signature} over {@code new ObjectMapper().writeValueAsString(body)},
 * where {@code body} is the {@code LinkedHashMap} its RestTemplate parsed. MockSync therefore serialises the envelope,
 * parses it back into a {@code LinkedHashMap} and serialises it again with a plain {@code ObjectMapper}; it sends and
 * signs exactly those bytes, so the client's re-serialisation reproduces them.
 */
@Component
public class ResponseFactory {

	/** Response header carrying the detached JWS of the body. */
	public static final String SIGNATURE_HEADER = "response-signature";

	private static final DateTimeFormatter RESPONSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

	private final ObjectMapper plainMapper = new ObjectMapper();
	private final ServerKeys keys;

	/**
	 * Creates the factory.
	 *
	 * @param keys server keys (KERNEL SIGN)
	 */
	public ResponseFactory(ServerKeys keys) {
		this.keys = keys;
	}

	/**
	 * Returns the current UTC time in the MOSIP response format.
	 *
	 * @return time text
	 */
	public static String now() {
		return ZonedDateTime.now(ZoneOffset.UTC).format(RESPONSE_TIME);
	}

	/**
	 * Builds a signed success envelope.
	 *
	 * @param id       response ID
	 * @param response response payload
	 * @return HTTP 200 with the {@value #SIGNATURE_HEADER} header
	 */
	public ResponseEntity<byte[]> signed(String id, Object response) {
		return build(envelope(id, response, null), true);
	}

	/**
	 * Builds an unsigned success envelope.
	 *
	 * @param id       response ID
	 * @param response response payload
	 * @return HTTP 200
	 */
	public ResponseEntity<byte[]> unsigned(String id, Object response) {
		return build(envelope(id, response, null), false);
	}

	/**
	 * Builds an error envelope (HTTP 200 with {@code errors}, as MOSIP services answer).
	 *
	 * @param id      response ID
	 * @param code    error code
	 * @param message error message
	 * @return HTTP 200 with an errors array
	 */
	public ResponseEntity<byte[]> error(String id, String code, String message) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("errorCode", code);
		error.put("message", message);
		return build(envelope(id, null, List.of(error)), true);
	}

	/**
	 * Serialises a body the way the client re-serialises it.
	 *
	 * @param body body object
	 * @return canonical JSON bytes
	 */
	public byte[] canonicalJson(Object body) {
		try {
			String first = plainMapper.writeValueAsString(body);
			Object reparsed = plainMapper.readValue(first, LinkedHashMap.class);
			return plainMapper.writeValueAsString(reparsed).getBytes(StandardCharsets.UTF_8);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot serialise response", e);
		}
	}

	private static Map<String, Object> envelope(String id, Object response, List<Map<String, Object>> errors) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("id", id);
		envelope.put("version", "1.0");
		envelope.put("responsetime", now());
		envelope.put("metadata", null);
		envelope.put("response", response);
		envelope.put("errors", errors);
		return envelope;
	}

	private ResponseEntity<byte[]> build(Map<String, Object> envelope, boolean sign) {
		byte[] body = canonicalJson(envelope);
		ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
		if (sign) {
			builder.header(SIGNATURE_HEADER, keys.signer().signDetached(body));
		}
		return builder.body(body);
	}
}
