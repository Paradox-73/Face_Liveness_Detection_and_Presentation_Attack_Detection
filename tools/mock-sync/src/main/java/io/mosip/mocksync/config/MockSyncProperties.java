/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under the {@code mocksync.} prefix (see {@code application.properties} and the README).
 */
@ConfigurationProperties(prefix = "mocksync")
public class MockSyncProperties {

	/** Directory for generated keys, registered machines and config overrides (gitignored). */
	private String workDir = "work";

	/** Directory with the committed reference data snapshots. */
	private String dataDir = "data";

	/** Plain HTTP port for the Android debug build; 0 disables it. */
	private int httpPort = 8080;

	/** Registration centre every registered machine and user belongs to. */
	private String centerId = "10001";

	/** Primary language code used for generated rows. */
	private String primaryLang = "eng";

	/** Server version reported by actuator/info and the ARC version check. */
	private String serverVersion = "1.2.0.2";

	/** Register unknown desktop machines from their tpm/publickey/verify call ("demo" profile). */
	private boolean autoRegisterMachines = true;

	/** Lifetime of issued auth tokens in seconds (default 180 days, so offline login keeps working). */
	private long tokenValiditySeconds = 180L * 24 * 3600;

	/** Auth processes where FACE is switched on in app_authentication_method. */
	private List<String> faceAuthProcesses = new ArrayList<>(
			List.of("login_auth", "packet_auth", "eod_auth", "exception_auth"));

	/** CA certificates served by getcacertificates, as {@code DOMAIN=path} (path relative to the start directory). */
	private List<String> caCertificates = new ArrayList<>();

	/** Value returned as response.authStatus by the IDA auth mock. */
	private boolean idaAuthStatus = true;

	/**
	 * Signed liveness model bundle ({@code .zip}) served by {@code GET /v1/syncdata/liveness/model-bundle}; empty
	 * answers 404.
	 */
	private String livenessModelBundle = "";

	/**
	 * Returns the work directory.
	 *
	 * @return path text
	 */
	public String getWorkDir() {
		return workDir;
	}

	/**
	 * Sets the work directory.
	 *
	 * @param workDir path text
	 */
	public void setWorkDir(String workDir) {
		this.workDir = workDir;
	}

	/**
	 * Returns the data directory.
	 *
	 * @return path text
	 */
	public String getDataDir() {
		return dataDir;
	}

	/**
	 * Sets the data directory.
	 *
	 * @param dataDir path text
	 */
	public void setDataDir(String dataDir) {
		this.dataDir = dataDir;
	}

	/**
	 * Returns the plain HTTP port.
	 *
	 * @return port, 0 when disabled
	 */
	public int getHttpPort() {
		return httpPort;
	}

	/**
	 * Sets the plain HTTP port.
	 *
	 * @param httpPort port, 0 disables
	 */
	public void setHttpPort(int httpPort) {
		this.httpPort = httpPort;
	}

	/**
	 * Returns the centre ID.
	 *
	 * @return centre ID
	 */
	public String getCenterId() {
		return centerId;
	}

	/**
	 * Sets the centre ID.
	 *
	 * @param centerId centre ID
	 */
	public void setCenterId(String centerId) {
		this.centerId = centerId;
	}

	/**
	 * Returns the primary language.
	 *
	 * @return language code
	 */
	public String getPrimaryLang() {
		return primaryLang;
	}

	/**
	 * Sets the primary language.
	 *
	 * @param primaryLang language code
	 */
	public void setPrimaryLang(String primaryLang) {
		this.primaryLang = primaryLang;
	}

	/**
	 * Returns the reported server version.
	 *
	 * @return version
	 */
	public String getServerVersion() {
		return serverVersion;
	}

	/**
	 * Sets the reported server version.
	 *
	 * @param serverVersion version
	 */
	public void setServerVersion(String serverVersion) {
		this.serverVersion = serverVersion;
	}

	/**
	 * Tells whether unknown machines are registered from tpm/publickey/verify.
	 *
	 * @return true when enabled
	 */
	public boolean isAutoRegisterMachines() {
		return autoRegisterMachines;
	}

	/**
	 * Enables or disables automatic machine registration.
	 *
	 * @param autoRegisterMachines flag
	 */
	public void setAutoRegisterMachines(boolean autoRegisterMachines) {
		this.autoRegisterMachines = autoRegisterMachines;
	}

	/**
	 * Returns the token lifetime.
	 *
	 * @return seconds
	 */
	public long getTokenValiditySeconds() {
		return tokenValiditySeconds;
	}

	/**
	 * Sets the token lifetime.
	 *
	 * @param tokenValiditySeconds seconds
	 */
	public void setTokenValiditySeconds(long tokenValiditySeconds) {
		this.tokenValiditySeconds = tokenValiditySeconds;
	}

	/**
	 * Returns the processes where FACE authentication is active.
	 *
	 * @return process IDs
	 */
	public List<String> getFaceAuthProcesses() {
		return faceAuthProcesses;
	}

	/**
	 * Sets the processes where FACE authentication is active.
	 *
	 * @param faceAuthProcesses process IDs
	 */
	public void setFaceAuthProcesses(List<String> faceAuthProcesses) {
		this.faceAuthProcesses = faceAuthProcesses;
	}

	/**
	 * Returns the CA certificate entries.
	 *
	 * @return {@code DOMAIN=path} entries
	 */
	public List<String> getCaCertificates() {
		return caCertificates;
	}

	/**
	 * Sets the CA certificate entries.
	 *
	 * @param caCertificates {@code DOMAIN=path} entries
	 */
	public void setCaCertificates(List<String> caCertificates) {
		this.caCertificates = caCertificates;
	}

	/**
	 * Returns the IDA auth status the mock answers.
	 *
	 * @return auth status
	 */
	public boolean isIdaAuthStatus() {
		return idaAuthStatus;
	}

	/**
	 * Sets the IDA auth status the mock answers.
	 *
	 * @param idaAuthStatus auth status
	 */
	public void setIdaAuthStatus(boolean idaAuthStatus) {
		this.idaAuthStatus = idaAuthStatus;
	}

	/**
	 * Returns the liveness model bundle file.
	 *
	 * @return path text, relative to the start directory; empty when none is served
	 */
	public String getLivenessModelBundle() {
		return livenessModelBundle;
	}

	/**
	 * Sets the liveness model bundle file.
	 *
	 * @param livenessModelBundle path text; empty serves none
	 */
	public void setLivenessModelBundle(String livenessModelBundle) {
		this.livenessModelBundle = livenessModelBundle;
	}
}
