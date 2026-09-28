package io.mosip.mocksync.crypto;

/**
 * Key storage type of a registration client machine. It decides how the AES session key of an
 * encrypted response is wrapped, the same way MOSIP keymanager {@code ClientCryptoFacade} does.
 */
public enum ClientType {

	/** Desktop client whose keys live in a TPM 2.0; public keys are {@code TPMT_PUBLIC} structures. */
	TPM,

	/** Android client (Android keystore); X.509 public keys, OAEP with SHA-256 and MGF1 SHA-1. */
	ANDROID,

	/** Desktop client without a TPM ({@code .mosipkeys/reg.pub}); OAEP with SHA-256 and MGF1 SHA-256. */
	LOCAL
}
