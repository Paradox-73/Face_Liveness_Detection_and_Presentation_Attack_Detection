/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Key pair, certificate and PKCS#12 helpers for the keys MockSync generates into its work directory.
 * All keys are RSA 2048 with SHA256withRSA certificates, like MOSIP keymanager defaults.
 */
public final class KeyMaterial {

	/** Password of every PKCS#12 file MockSync writes. The files are local test keys and never leave work/. */
	public static final char[] STORE_PASSWORD = "mocksync".toCharArray();

	private static final SecureRandom RANDOM = new SecureRandom();

	private KeyMaterial() {
	}

	/**
	 * Generates an RSA 2048 key pair.
	 *
	 * @return key pair
	 */
	public static KeyPair newRsaKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048, RANDOM);
			return generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("RSA not available", e);
		}
	}

	/**
	 * Creates a certificate for a key pair, self-signed when {@code issuer} is null.
	 *
	 * @param keyPair   subject key pair
	 * @param subjectDn subject distinguished name, e.g. {@code CN=MockSync KERNEL SIGN,O=MockSync}
	 * @param validity  validity from one day in the past
	 * @param ca        true to mark the certificate as a CA
	 * @param dnsNames  DNS subject alternative names, may be empty
	 * @param ipNames   IP subject alternative names, may be empty
	 * @param issuer    issuing key, or null for self-signed
	 * @return the certificate
	 */
	public static X509Certificate certificate(KeyPair keyPair, String subjectDn, Duration validity, boolean ca,
			List<String> dnsNames, List<String> ipNames, CertifiedKey issuer) {
		try {
			Instant notBefore = Instant.now().minus(Duration.ofDays(1));
			X500Name subject = new X500Name(subjectDn);
			X500Name issuerName = issuer == null ? subject
					: X500Name.getInstance(issuer.getCertificate().getSubjectX500Principal().getEncoded());
			X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName,
					new BigInteger(64, RANDOM).abs(), Date.from(notBefore), Date.from(notBefore.plus(validity)), subject,
					keyPair.getPublic());
			JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
			builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(keyPair.getPublic()));
			builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
			builder.addExtension(Extension.keyUsage, true, ca
					? new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature)
					: new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment));
			if (issuer != null) {
				builder.addExtension(Extension.authorityKeyIdentifier, false,
						utils.createAuthorityKeyIdentifier(issuer.getCertificate()));
			}
			List<GeneralName> names = new ArrayList<>();
			dnsNames.forEach(name -> names.add(new GeneralName(GeneralName.dNSName, name)));
			ipNames.forEach(ip -> names.add(new GeneralName(GeneralName.iPAddress, ip)));
			if (!names.isEmpty()) {
				builder.addExtension(Extension.subjectAlternativeName, false,
						new GeneralNames(names.toArray(new GeneralName[0])));
			}
			PrivateKey signingKey = issuer == null ? keyPair.getPrivate() : issuer.getPrivateKey();
			ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(signingKey);
			return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
		} catch (IOException | GeneralSecurityException | OperatorCreationException e) {
			throw new IllegalStateException("Certificate generation failed for " + subjectDn, e);
		}
	}

	/**
	 * Writes a key and its certificate chain to a PKCS#12 file.
	 *
	 * @param file  target file
	 * @param alias entry alias
	 * @param key   private key
	 * @param chain certificate chain, leaf first
	 * @throws IOException if the file cannot be written
	 */
	public static void savePkcs12(Path file, String alias, PrivateKey key, X509Certificate... chain) throws IOException {
		try {
			KeyStore store = KeyStore.getInstance("PKCS12");
			store.load(null, null);
			store.setKeyEntry(alias, key, STORE_PASSWORD, chain);
			Files.createDirectories(file.toAbsolutePath().getParent());
			try (OutputStream out = Files.newOutputStream(file)) {
				store.store(out, STORE_PASSWORD);
			}
		} catch (GeneralSecurityException e) {
			throw new IOException("Cannot write " + file, e);
		}
	}

	/**
	 * Reads the first key entry of a PKCS#12 file written by {@link #savePkcs12}.
	 *
	 * @param file PKCS#12 file
	 * @return key and leaf certificate
	 * @throws IOException if the file cannot be read
	 */
	public static CertifiedKey loadPkcs12(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			KeyStore store = KeyStore.getInstance("PKCS12");
			store.load(in, STORE_PASSWORD);
			String alias = store.aliases().nextElement();
			return new CertifiedKey((PrivateKey) store.getKey(alias, STORE_PASSWORD),
					(X509Certificate) store.getCertificate(alias));
		} catch (GeneralSecurityException e) {
			throw new IOException("Cannot read " + file, e);
		}
	}

	/**
	 * Formats a certificate as PEM with LF line endings, the format keymanager {@code convertToCertificate} parses.
	 *
	 * @param certificate certificate
	 * @return PEM text
	 */
	public static String toPem(Certificate certificate) {
		StringWriter writer = new StringWriter();
		try (JcaPEMWriter pem = new JcaPEMWriter(writer)) {
			pem.writeObject(certificate);
		} catch (IOException e) {
			throw new IllegalStateException("PEM encoding failed", e);
		}
		return writer.toString().replace("\r\n", "\n");
	}

	/**
	 * Parses a PEM or DER certificate.
	 *
	 * @param data certificate bytes
	 * @return certificate
	 * @throws GeneralSecurityException if the data is not a certificate
	 */
	public static X509Certificate parseCertificate(byte[] data) throws GeneralSecurityException {
		return (X509Certificate) CertificateFactory.getInstance("X.509")
				.generateCertificate(new java.io.ByteArrayInputStream(data));
	}

	/**
	 * SHA-256 thumbprint of a certificate's DER encoding as lowercase hex.
	 *
	 * @param certificate certificate
	 * @return hex thumbprint
	 */
	public static String thumbprintHex(X509Certificate certificate) {
		try {
			return ClientCrypto.sha256Hex(certificate.getEncoded());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Returns the host names a TLS certificate for MockSync should cover.
	 *
	 * @return DNS names
	 */
	public static List<String> localDnsNames() {
		List<String> names = new ArrayList<>(List.of("localhost"));
		try {
			String host = InetAddress.getLocalHost().getHostName().toLowerCase();
			if (!names.contains(host)) {
				names.add(host);
			}
		} catch (IOException ignored) {
			// host name is optional in the certificate
		}
		return names;
	}
}
