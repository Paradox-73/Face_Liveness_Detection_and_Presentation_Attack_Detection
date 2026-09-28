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
