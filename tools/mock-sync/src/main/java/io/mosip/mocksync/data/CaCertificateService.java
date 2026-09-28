package io.mosip.mocksync.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.KeyMaterial;
import io.mosip.mocksync.crypto.ServerKeys;

/**
 * Builds {@code certificateDTOList} for {@code GET /v1/syncdata/getcacertificates}. The desktop client uploads every
 * certificate whose {@code partnerDomain} is one of its device trust domains (DEVICE, FTM) into its local CA store,
 * which is the only way device (SBI) certificates become trusted. MockSync serves the certificates listed in
 * {@code mocksync.ca-certificates} (MockMDS {@code rootca.cer} and {@code rootcaftm.cer} by default) plus its own
 * DEVICE CA.
 */
@Component
public class CaCertificateService {

	private static final Logger LOGGER = LoggerFactory.getLogger(CaCertificateService.class);

	private final MockSyncProperties properties;
	private final ServerKeys keys;

	/**
	 * Creates the service.
	 *
	 * @param properties MockSync settings (certificate list)
	 * @param keys       server keys (DEVICE CA)
	 */
	public CaCertificateService(MockSyncProperties properties, ServerKeys keys) {
		this.properties = properties;
		this.keys = keys;
	}

	/**
	 * Returns the certificate entries in upload order.
	 *
	 * @return list of CaCertificateDto-shaped maps
	 */
	public List<Map<String, Object>> certificates() {
		List<Map<String, Object>> list = new ArrayList<>();
		for (String entry : properties.getCaCertificates()) {
			int eq = entry.indexOf('=');
			if (eq < 0) {
				LOGGER.warn("Ignoring CA entry without DOMAIN=path: {}", entry);
				continue;
			}
			String domain = entry.substring(0, eq).trim().toUpperCase(Locale.ROOT);
			Path file = Path.of(entry.substring(eq + 1).trim());
			try {
				X509Certificate cert = KeyMaterial.parseCertificate(Files.readAllBytes(file));
				warnIfExpired(cert, file);
				list.add(dto(cert, domain, list.size()));
			} catch (IOException | GeneralSecurityException e) {
				LOGGER.warn("Cannot read CA certificate {} for {}: {}", file.toAbsolutePath(), domain, e.getMessage());
			}
		}
		list.add(dto(keys.deviceCa().getCertificate(), "DEVICE", list.size()));
		return list;
	}

	private static void warnIfExpired(X509Certificate cert, Path file) {
		if (cert.getNotAfter().before(new Date())) {
			LOGGER.warn("CA certificate {} expired on {}; the client's keymanager may reject it", file, cert.getNotAfter());
		}
	}

	private static Map<String, Object> dto(X509Certificate cert, String domain, int order) {
		Map<String, Object> dto = new LinkedHashMap<>();
		String thumbprint = KeyMaterial.thumbprintHex(cert);
		dto.put("certId", thumbprint.substring(0, 32));
		dto.put("certSubject", cert.getSubjectX500Principal().getName());
		dto.put("certIssuer", cert.getIssuerX500Principal().getName());
		dto.put("issuerId", null);
		dto.put("certNotBefore", toLocal(cert.getNotBefore()));
		dto.put("certNotAfter", toLocal(cert.getNotAfter()));
		dto.put("crlUri", null);
		dto.put("certData", KeyMaterial.toPem(cert));
		dto.put("certThumbprint", thumbprint);
		dto.put("certSerialNo", cert.getSerialNumber().toString());
		dto.put("partnerDomain", domain);
		dto.put("createdBy", "mock-sync");
		dto.put("createdtimes", LocalDateTime.of(2021, 1, 1, 0, 0).plusMinutes(order).toString() + ":00");
		dto.put("updatedBy", null);
		dto.put("updatedtimes", null);
		dto.put("isDeleted", Boolean.FALSE);
		dto.put("deletedtimes", null);
		return dto;
	}

	private static String toLocal(Date date) {
		return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC).withNano(0).toString();
	}
}
