/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.machine;

import io.mosip.mocksync.crypto.ClientType;

/**
 * One registration client machine known to MockSync (the admin portal's machine master row plus keys).
 */
public class Machine {

	private String name;
	private String machineId;
	private String centerId;
	private ClientType clientType;
	private String publicKey;
	private String signPublicKey;
	private String keyIndex;
	private String signKeyIndex;
	private boolean active = true;
	private String registeredAt;
	private String registeredBy;

	/**
	 * Returns the machine name: the lower-case host name for desktop, the generated name for Android.
	 *
	 * @return name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the machine name.
	 *
	 * @param name name
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Returns the machine ID used in RIDs and the REGISTRATION key reference.
	 *
	 * @return machine ID
	 */
	public String getMachineId() {
		return machineId;
	}

	/**
	 * Sets the machine ID.
	 *
	 * @param machineId machine ID
	 */
	public void setMachineId(String machineId) {
		this.machineId = machineId;
	}

	/**
	 * Returns the registration centre ID.
	 *
	 * @return centre ID
	 */
	public String getCenterId() {
		return centerId;
	}

	/**
	 * Sets the registration centre ID.
	 *
	 * @param centerId centre ID
	 */
	public void setCenterId(String centerId) {
		this.centerId = centerId;
	}

	/**
	 * Returns the key storage type.
	 *
	 * @return client type
	 */
	public ClientType getClientType() {
		return clientType;
	}

	/**
	 * Sets the key storage type.
	 *
	 * @param clientType client type
	 */
	public void setClientType(ClientType clientType) {
		this.clientType = clientType;
	}

	/**
	 * Returns the encryption public key, base64url.
	 *
	 * @return key text
	 */
	public String getPublicKey() {
		return publicKey;
	}

	/**
	 * Sets the encryption public key, base64url.
	 *
	 * @param publicKey key text
	 */
	public void setPublicKey(String publicKey) {
		this.publicKey = publicKey;
	}

	/**
	 * Returns the signing public key, base64url.
	 *
	 * @return key text
	 */
	public String getSignPublicKey() {
		return signPublicKey;
	}

	/**
	 * Sets the signing public key, base64url.
	 *
	 * @param signPublicKey key text
	 */
	public void setSignPublicKey(String signPublicKey) {
		this.signPublicKey = signPublicKey;
	}

	/**
	 * Returns the fingerprint of the encryption key (the client's {@code keyindex}).
	 *
	 * @return key index
	 */
	public String getKeyIndex() {
		return keyIndex;
	}

	/**
	 * Sets the key index.
	 *
	 * @param keyIndex key index
	 */
	public void setKeyIndex(String keyIndex) {
		this.keyIndex = keyIndex;
	}

	/**
	 * Returns the fingerprint of the signing key (the login JWT {@code kid}).
	 *
	 * @return sign key index
	 */
	public String getSignKeyIndex() {
		return signKeyIndex;
	}

	/**
	 * Sets the sign key index.
	 *
	 * @param signKeyIndex sign key index
	 */
	public void setSignKeyIndex(String signKeyIndex) {
		this.signKeyIndex = signKeyIndex;
	}

	/**
	 * Tells whether the machine is active.
	 *
	 * @return active flag
	 */
	public boolean isActive() {
		return active;
	}

	/**
	 * Sets the active flag.
	 *
	 * @param active active flag
	 */
	public void setActive(boolean active) {
		this.active = active;
	}

	/**
	 * Returns the registration time (ISO-8601).
	 *
	 * @return time text
	 */
	public String getRegisteredAt() {
		return registeredAt;
	}

	/**
	 * Sets the registration time.
	 *
	 * @param registeredAt time text
	 */
	public void setRegisteredAt(String registeredAt) {
		this.registeredAt = registeredAt;
	}

	/**
	 * Returns how the machine was registered ({@code admin}, {@code readme}, {@code auto}).
	 *
	 * @return source
	 */
	public String getRegisteredBy() {
		return registeredBy;
	}

	/**
	 * Sets how the machine was registered.
	 *
	 * @param registeredBy source
	 */
	public void setRegisteredBy(String registeredBy) {
		this.registeredBy = registeredBy;
	}
}
