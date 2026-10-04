/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.crypto;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * A private key with its certificate, as held by one MockSync key role (KERNEL SIGN, IDA, DEVICE CA, ...).
 */
public final class CertifiedKey {

	private final PrivateKey privateKey;
	private final X509Certificate certificate;

	/**
	 * Creates the pair.
	 *
	 * @param privateKey  private key
	 * @param certificate certificate whose public key matches the private key
	 */
	public CertifiedKey(PrivateKey privateKey, X509Certificate certificate) {
		this.privateKey = privateKey;
		this.certificate = certificate;
	}

	/**
	 * Returns the private key.
	 *
	 * @return private key
	 */
	public PrivateKey getPrivateKey() {
		return privateKey;
	}

	/**
	 * Returns the certificate.
	 *
	 * @return certificate
	 */
	public X509Certificate getCertificate() {
		return certificate;
	}
}
