package io.mosip.mocksync.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pre-registration sync stand-in. MockSync has no pre-registration data: the ID list is always empty, so the
 * client's scheduled pre-registration sync job completes without downloading anything.
 */
@RestController
@RequestMapping("/preregistration/v1/sync")
public class PreRegistrationController {

	private final ResponseFactory responses;

	/**
	 * Creates the controller.
	 *
	 * @param responses response factory
	 */
	public PreRegistrationController(ResponseFactory responses) {
		this.responses = responses;
	}

	/**
	 * Lists pre-registration IDs for the centre (always none).
	 *
	 * @return signed envelope with an empty {@code preRegistrationIds} map
	 */
	@PostMapping
	public ResponseEntity<byte[]> preRegistrationIds() {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("transactionId", UUID.randomUUID().toString());
		response.put("countOfPreRegIds", "0");
		response.put("preRegistrationIds", Map.of());
		return responses.signed("mosip.pre-registration.datasync.fetch.ids", response);
	}

	/**
	 * Downloads one pre-registration packet (never available).
	 *
	 * @param preRegistrationId pre-registration ID
	 * @param stationId         machine ID
	 * @return error envelope
	 */
	@GetMapping("/{preRegistrationId}/{stationId}")
	public ResponseEntity<byte[]> preRegistration(@PathVariable String preRegistrationId, @PathVariable String stationId) {
		return responses.error("mosip.pre-registration.datasync.fetch", "PRG_DATA_SYNC_022",
				"Pre-registration " + preRegistrationId + " not found in MockSync");
	}
}
