/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * The device key trust model on a real Android Keystore: a self-signed key before provisioning, and the import of
 * a provisioned PKCS#12 file in the format {@code scripts/android-liveness-device-cert.sh} writes (SHA-1/3DES).
 *
 * <p>The asset {@code liveness-test/device.p12} holds a throwaway key and a certificate from a throwaway test CA
 * (password {@code test-only}); it is not trusted anywhere.
 */
@RunWith(AndroidJUnit4.class)
public class AndroidKeystoreSignerTest {

    private static final byte[] DATA = "liveness capture".getBytes(StandardCharsets.UTF_8);

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private File dir;

    @Before
    public void setUp() throws Exception {
        deleteAlias();
        dir = new File(context.getCacheDir(), "liveness-signer-test");
        dir.mkdirs();
    }

    @After
    public void tearDown() throws Exception {
        deleteAlias();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    @Test
    public void unprovisionedDeviceSignsWithASelfSignedKey() throws Exception {
        AndroidKeystoreSigner signer = new AndroidKeystoreSigner(dir, "TEST-SERIAL");

        byte[] signature = signer.sign(DATA);
        List<X509Certificate> chain = signer.certificateChain();

        assertEquals(1, chain.size());
        assertFalse(signer.isProvisioned());
        assertTrue(verifies(chain.get(0), signature));
    }

    @Test
    public void provisionedKeyIsImportedAndTheFilesAreDeleted() throws Exception {
        File p12 = new File(dir, AndroidKeystoreSigner.PROVISION_FILE);
        File pass = new File(dir, AndroidKeystoreSigner.PROVISION_PASSWORD_FILE);
        try (InputStream in = context.getAssets().open("liveness-test/device.p12");
             OutputStream out = new FileOutputStream(p12)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
        Files.write(pass.toPath(), "test-only\n".getBytes(StandardCharsets.UTF_8));
        AndroidKeystoreSigner signer = new AndroidKeystoreSigner(dir, "TEST-SERIAL");

        assertTrue(signer.isProvisioned());
        List<X509Certificate> chain = signer.certificateChain();
        assertEquals(2, chain.size());
        assertTrue(chain.get(0).getSubjectX500Principal().getName().contains("CN=TEST-DEVICE"));
        assertFalse("key file deleted after import", p12.exists());
        assertFalse("password file deleted after import", pass.exists());
        assertTrue(verifies(chain.get(0), signer.sign(DATA)));
        assertArrayEquals(chain.get(0).getEncoded(),
                new AndroidKeystoreSigner(dir, "TEST-SERIAL").certificateChain().get(0).getEncoded());
    }

    private static boolean verifies(X509Certificate certificate, byte[] signature) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(certificate.getPublicKey());
        s.update(DATA);
        return s.verify(signature);
    }

    private static void deleteAlias() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(AndroidKeystoreSigner.ALIAS)) {
            ks.deleteEntry(AndroidKeystoreSigner.ALIAS);
        }
    }
}
