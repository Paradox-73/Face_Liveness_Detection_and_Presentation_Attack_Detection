/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * The signing key of the built-in L0 face device: signs the SBI capture and digital ID with RS256.
 */
public interface DeviceSigner {

    /**
     * Signs data with RSA PKCS#1 v1.5 and SHA-256 (JWS {@code RS256}).
     *
     * @param data bytes to sign
     * @return the signature
     * @throws GeneralSecurityException if the key cannot sign
     */
    byte[] sign(byte[] data) throws GeneralSecurityException;

    /**
     * Returns the device certificate first, then its issuers; put into the JWS {@code x5c} header so ARC can
     * check the signature against its DEVICE trust store.
     *
     * @return the chain; empty if the key has no certificate (tests)
     * @throws GeneralSecurityException if the key store cannot be read
     */
    List<X509Certificate> certificateChain() throws GeneralSecurityException;

    /**
     * Tells whether the certificate was issued by a certificate authority (provisioned) rather than self-signed.
     * Only an issued certificate can pass ARC's DEVICE trust check.
     *
     * @return {@code true} for a provisioned device certificate
     */
    boolean isProvisioned();
}
