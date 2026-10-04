/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javax.security.auth.x500.X500Principal;

/**
 * {@link DeviceSigner} whose private key lives in the Android Keystore and never leaves it.
 *
 * <p><b>Trust model</b> (SBI L0: "trust is provided at the software level"). The key is created in, or imported
 * into, the Android Keystore under {@value #ALIAS}; the app can use it but cannot export it. Its certificate
 * decides whether ARC trusts the captures:
 * <ul>
 *   <li><b>Provisioned in the app</b> (release and debug builds): the key is created in the Keystore and the app
 *   writes a PKCS#10 certificate request for it to its app-specific external files folder
 *   ({@code Android/data/<package>/files/liveness/}, see {@link DeviceProvisioning}). A DEVICE certificate
 *   authority that ARC syncs (MockSync's DEVICE CA for the demo; in production the device provider's CA from MOSIP
 *   partner management) signs it and the chain is placed next to it. Before the chain is used, at the next
 *   capture or provisioning request, the app checks that it holds this key's public key and was issued by the CA
 *   in the chain (once provisioned: by the same CA as before). {@code scripts/android-liveness-device-cert.sh
 *   --csr} does this over adb through {@link DeviceProvisioningActivity}.</li>
 *   <li><b>Provisioned from a key file</b> (debug builds): a PKCS#12 file with a device key and a certificate
 *   issued by such a CA is placed in {@code <files>/liveness/device.p12} with its password in
 *   {@code device.p12.pass}. It is imported at the next use and both files are deleted.
 *   {@code scripts/android-liveness-device-cert.sh} without {@code --csr} does this with {@code adb shell run-as},
 *   which works for debuggable builds only.</li>
 *   <li><b>Not provisioned</b>: a self-signed key is created on first use. Liveness works, but ARC's unchanged
 *   SBI check ({@code Biometrics095Service.handleRCaptureResponse}, DEVICE trust) rejects the capture, so a
 *   resident capture cannot be stored until the device is provisioned.</li>
 * </ul>
 */
public final class AndroidKeystoreSigner implements DeviceSigner {

    /** Android Keystore alias of the device key. */
    public static final String ALIAS = "mosip-liveness-face-device";
    /** File name of a device key waiting to be imported. */
    public static final String PROVISION_FILE = "device.p12";
    /** File name of its password (UTF-8 text). */
    public static final String PROVISION_PASSWORD_FILE = "device.p12.pass";

    private static final String TAG = "Liveness";
    private static final String KEYSTORE = "AndroidKeyStore";

    /** The in-app provisioning exchange folder, set when the app starts ({@link DeviceProvisioningInitializer}). */
    private static volatile Supplier<File> requestFolder = () -> null;

    private final File provisionDir;
    private final String subjectName;

    /**
     * Creates the signer.
     *
     * @param provisionDir folder searched for {@value #PROVISION_FILE} (app-private storage)
     * @param subjectName  common name of a self-signed certificate, for example the device serial number
     */
    public AndroidKeystoreSigner(File provisionDir, String subjectName) {
        this.provisionDir = Objects.requireNonNull(provisionDir, "provisionDir");
        this.subjectName = Objects.requireNonNull(subjectName, "subjectName");
    }

    /**
     * Sets where the in-app provisioning files are exchanged (the app-specific external files folder).
     *
     * @param folder supplies the folder, or {@code null} while external storage is not available
     */
    static void useRequestFolder(Supplier<File> folder) {
        requestFolder = Objects.requireNonNull(folder, "folder");
    }

    @Override
    public synchronized byte[] sign(byte[] data) throws GeneralSecurityException {
        return new KeystoreKey(keyStore()).sign(data);
    }

    @Override
    public synchronized List<X509Certificate> certificateChain() throws GeneralSecurityException {
        Certificate[] chain = keyStore().getCertificateChain(ALIAS);
        if (chain == null) {
            return Collections.emptyList();
        }
        List<X509Certificate> list = new ArrayList<>();
        for (Certificate c : chain) {
            list.add((X509Certificate) c);
        }
        return list;
    }

    @Override
    public synchronized boolean isProvisioned() {
        try {
            return DeviceProvisioning.isCaIssued(certificateChain());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Runs one in-app provisioning step: creates the key when missing, imports a waiting certificate chain after
     * checking it, writes a new certificate request and the status (see {@link DeviceProvisioning}).
     *
     * @param requestId  identifier echoed in the status file
     * @param allowNewCa {@code true} to accept a chain from another CA than the one of the current certificate
     * @return the import outcome
     * @throws GeneralSecurityException if the key store cannot be used
     * @throws IOException              if the exchange folder is not available or cannot be written
     */
    public synchronized DeviceProvisioning.Outcome provision(String requestId, boolean allowNewCa)
            throws GeneralSecurityException, IOException {
        File folder = requestFolder.get();
        if (folder == null) {
            throw new IOException("app-specific external storage is not available");
        }
        DeviceProvisioning.Outcome outcome = provisioning(openKeyStore(), folder).run(requestId, allowNewCa);
        log(outcome);
        return outcome;
    }

    /**
     * Opens the key store (see {@link #openKeyStore()}), then imports a certificate chain returned by the device CA
     * after checking it.
     */
    private KeyStore keyStore() throws GeneralSecurityException {
        KeyStore ks = openKeyStore();
        importWaitingChain(ks);
        return ks;
    }

    /** Opens the key store, importing a waiting PKCS#12 file or creating a self-signed key when needed. */
    private KeyStore openKeyStore() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        try {
            ks.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException("Android Keystore cannot be opened", e);
        }
        File p12 = new File(provisionDir, PROVISION_FILE);
        if (p12.isFile()) {
            importPkcs12(ks, p12, new File(provisionDir, PROVISION_PASSWORD_FILE));
        }
        if (!ks.containsAlias(ALIAS)) {
            generateSelfSigned();
        }
        return ks;
    }

    /** Imports a chain the device CA returned, after checking it; a capture never fails because of this step. */
    private void importWaitingChain(KeyStore ks) {
        File folder = requestFolder.get();
        if (folder == null || !new File(folder, DeviceProvisioning.CHAIN_FILE).isFile()) {
            return;
        }
        try {
            DeviceProvisioning p = provisioning(ks, folder);
            DeviceProvisioning.Outcome outcome = p.importWaitingChain(false);
            log(outcome);
            p.writeStatus("", outcome);
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            Log.w(TAG, "Device certificate chain not imported: " + e.getMessage());
        }
    }

    private static DeviceProvisioning provisioning(KeyStore ks, File folder) {
        return new DeviceProvisioning(new KeystoreKey(ks), folder, Clock.systemUTC());
    }

    private static void log(DeviceProvisioning.Outcome outcome) {
        if (outcome.kind() == DeviceProvisioning.Outcome.Kind.ACCEPTED) {
            Log.i(TAG, "Built-in face device certificate installed; " + outcome.detail());
        } else if (outcome.kind() == DeviceProvisioning.Outcome.Kind.REFUSED) {
            Log.w(TAG, "Built-in face device certificate refused: " + outcome.detail());
        }
    }

    private void importPkcs12(KeyStore ks, File p12, File passwordFile) throws GeneralSecurityException {
        try {
            char[] password = passwordFile.isFile()
                    ? new String(Files.readAllBytes(passwordFile.toPath()), StandardCharsets.UTF_8).trim()
                    .toCharArray() : new char[0];
            KeyStore source = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(p12)) {
                source.load(in, password);
            }
            String alias = firstKeyAlias(source);
            PrivateKey key = (PrivateKey) source.getKey(alias, password);
            Certificate[] chain = source.getCertificateChain(alias);
            if (ks.containsAlias(ALIAS)) {
                ks.deleteEntry(ALIAS);
            }
            ks.setEntry(ALIAS, new KeyStore.PrivateKeyEntry(key, chain),
                    new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                            .build());
            Log.i(TAG, "Built-in face device key imported; certificate issued by "
                    + ((X509Certificate) chain[0]).getIssuerX500Principal().getName());
        } catch (IOException e) {
            throw new GeneralSecurityException("device key file cannot be read", e);
        } finally {
            if (!p12.delete() || (passwordFile.exists() && !passwordFile.delete())) {
                Log.w(TAG, "Provisioned device key file could not be deleted");
            }
        }
    }

    private static String firstKeyAlias(KeyStore source) throws GeneralSecurityException {
        Enumeration<String> aliases = source.aliases();
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            if (source.isKeyEntry(a)) {
                return a;
            }
        }
        throw new GeneralSecurityException("device key file holds no private key");
    }

    private void generateSelfSigned() throws GeneralSecurityException {
        Calendar notAfter = Calendar.getInstance();
        notAfter.add(Calendar.YEAR, 10);
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE);
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=" + subjectName.replace(',', ' ')
                        + ",OU=Built-in face camera,O=MOSIP Registration Client"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(new Date())
                .setCertificateNotAfter(notAfter.getTime())
                .build());
        g.generateKeyPair();
        Log.i(TAG, "Built-in face device key created (self-signed; not yet provisioned)");
    }

    /** The Keystore entry {@value #ALIAS} as {@link DeviceProvisioning} sees it. */
    private static final class KeystoreKey implements DeviceProvisioning.DeviceKey {
        private final KeyStore ks;

        KeystoreKey(KeyStore ks) {
            this.ks = ks;
        }

        @Override
        public List<X509Certificate> chain() throws GeneralSecurityException {
            Certificate[] chain = ks.getCertificateChain(ALIAS);
            if (chain == null || chain.length == 0) {
                throw new GeneralSecurityException("device key has no certificate");
            }
            List<X509Certificate> list = new ArrayList<>();
            for (Certificate c : chain) {
                list.add((X509Certificate) c);
            }
            return list;
        }

        @Override
        public byte[] sign(byte[] data) throws GeneralSecurityException {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign((PrivateKey) ks.getKey(ALIAS, null));
            s.update(data);
            return s.sign();
        }

        @Override
        public void installChain(List<X509Certificate> chain) throws GeneralSecurityException {
            // Only the certificates change: the Keystore keeps the private key it created or imported.
            ks.setKeyEntry(ALIAS, ks.getKey(ALIAS, null), null, chain.toArray(new Certificate[0]));
            Certificate[] stored = ks.getCertificateChain(ALIAS);
            if (stored == null || !Arrays.equals(stored[0].getEncoded(), chain.get(0).getEncoded())) {
                throw new GeneralSecurityException("Android Keystore did not keep the device certificate");
            }
        }
    }
}
