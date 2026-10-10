/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.entity.LocalPreferences;
import io.mosip.registration.repositories.LocalPreferencesRepository;
import io.mosip.registration.util.healthcheck.RegistrationSystemPropertiesChecker;

/**
 * Saves the liveness {@link AttemptHistory} in the client's local database, as rows of the {@code local_preferences}
 * table with their own config type, so a client restart keeps counted failures and blocks. The settings screen
 * reads only its own config type, so these rows never act as settings.
 *
 * <p>A database error is logged and the history keeps the value in memory.
 */
final class LocalAttemptStore implements AttemptHistory.Store {

	/** {@code config_type} of the saved rows. */
	static final String CONFIG_TYPE = "LIVENESS_ATTEMPTS";
	private static final String PREFIX = "liveness.attempts.";
	/** Length of {@code local_preferences.name}. */
	private static final int MAX_NAME = 128;

	private static final Logger LOGGER = AppConfig.getLogger(LocalAttemptStore.class);

	private final LocalPreferencesRepository repository;

	LocalAttemptStore(LocalPreferencesRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository");
	}

	@Override
	public Map<String, String> load() {
		Map<String, String> saved = new HashMap<>();
		try {
			for (LocalPreferences row : repository.findByIsDeletedFalseAndConfigType(CONFIG_TYPE)) {
				if (row.getName() != null && row.getName().startsWith(PREFIX) && row.getVal() != null) {
					saved.put(row.getName().substring(PREFIX.length()), row.getVal());
				}
			}
		} catch (RuntimeException e) {
			LOGGER.error("Saved liveness attempts could not be read", e);
		}
		return saved;
	}

	@Override
	public void put(String key, String value) {
		String name = PREFIX + key;
		if (name.length() > MAX_NAME) {
			LOGGER.warn("Liveness attempts of a subject with a very long ID are kept in memory only");
			return;
		}
		try {
			LocalPreferences row = repository.findByIsDeletedFalseAndName(name);
			Timestamp now = Timestamp.valueOf(DateUtils.getUTCCurrentDateTime());
			if (row == null) {
				row = new LocalPreferences();
				row.setId(UUID.randomUUID().toString());
				row.setName(name);
				row.setConfigType(CONFIG_TYPE);
				row.setMachineName(RegistrationSystemPropertiesChecker.getMachineId());
				row.setCrBy(RegistrationConstants.JOB_TRIGGER_POINT_USER);
				row.setCrDtime(now);
				row.setIsDeleted(false);
			} else {
				row.setUpdBy(RegistrationConstants.JOB_TRIGGER_POINT_USER);
				row.setUpdDtimes(now);
			}
			row.setVal(value);
			repository.save(row);
		} catch (RuntimeException e) {
			LOGGER.error("Liveness attempts could not be saved", e);
		}
	}

	@Override
	public void remove(String key) {
		try {
			LocalPreferences row = repository.findByIsDeletedFalseAndName(PREFIX + key);
			if (row != null) {
				repository.delete(row);
			}
		} catch (RuntimeException e) {
			LOGGER.error("Liveness attempts could not be removed", e);
		}
	}
}
