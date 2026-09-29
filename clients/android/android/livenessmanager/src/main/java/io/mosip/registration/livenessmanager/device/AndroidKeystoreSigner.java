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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

import javax.security.auth.x500.X500Principal;

/**
 * {@link DeviceSigner} whose private key lives in the Android Keystore and never leaves it.
 *
 * <p><b>Trust model</b> (SBI L0: "trust is provided at the software level"). The key is created in, or imported
 * into, the Android Keystore under {@value #ALIAS}; the app can use it but cannot export it. Its certificate
 * decides whether ARC trusts the captures:
 * <ul>
 *   <li><b>Provisioned</b>: a PKCS#12 file with a device key and a certificate issued by a DEVICE certificate
 *   authority that ARC syncs (MockSync's DEVICE CA for the demo; in production the device provider's
 *   certificate from MOSIP partner management) is placed in {@code <files>/liveness/device.p12} with its password
 *   in {@code device.p12.pass}. It is imported at the next use and both files are deleted.
 *   {@code scripts/android-liveness-device-cert.sh} does this for debug builds.</li>
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

    @Override
    public synchronized byte[] sign(byte[] data) throws GeneralSecurityException {
        KeyStore ks = keyStore();
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign((PrivateKey) ks.getKey(ALIAS, null));
        s.update(data);
        return s.sign();
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
            List<X509Certificate> chain = certificateChain();
            return !chain.isEmpty()
                    && !chain.get(0).getIssuerX500Principal().equals(chain.get(0).getSubjectX500Principal());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** Opens the key store, importing a waiting PKCS#12 file or creating a self-signed key when needed. */
    private KeyStore keyStore() throws GeneralSecurityException {
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
}
