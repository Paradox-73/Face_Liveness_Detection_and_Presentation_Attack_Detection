/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.mosip.mocksync.config.MockSyncProperties;

/**
 * Online liveness model update: serves the signed model bundle named by {@code mocksync.liveness-model-bundle}. The
 * clients download it from the URL in the synced key {@code mosip.registration.liveness.model.update_url} and run
 * their offline import checks on it (signature, hashes, version floor), so the file needs no protection here.
 */
@RestController
public class ModelBundleController {

	/** Path of the bundle download. */
	public static final String PATH = "/v1/syncdata/liveness/model-bundle";

	private static final Logger LOGGER = LoggerFactory.getLogger(ModelBundleController.class);
	private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

	private final MockSyncProperties properties;

	/**
	 * Creates the controller.
	 *
	 * @param properties MockSync settings
	 */
	public ModelBundleController(MockSyncProperties properties) {
		this.properties = properties;
	}

	/**
	 * Streams the configured bundle.
	 *
	 * @return the {@code .zip} file; 404 when none is configured or the file is missing
	 */
	@GetMapping(PATH)
	public ResponseEntity<Resource> modelBundle() {
		String file = properties.getLivenessModelBundle();
		Path path = file == null || file.isBlank() ? null : Path.of(file);
		if (path == null || !Files.isRegularFile(path)) {
			LOGGER.info("Liveness model bundle requested; none served (mocksync.liveness-model-bundle={})", file);
			return ResponseEntity.notFound().build();
		}
		LOGGER.info("Liveness model bundle served: {}", path.getFileName());
		return ResponseEntity.ok().contentType(ZIP).body(new FileSystemResource(path));
	}
}
