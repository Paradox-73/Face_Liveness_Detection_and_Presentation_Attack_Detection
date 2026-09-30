package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;

import org.junit.Test;

import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.registration.service.bio.liveness.LivenessModelImporter.Status;

/**
 * Texts of the model import window (B6): every import status has a text in every language, the result texts fill
 * their versions, and only supervisors see the menu item.
 */
public class LivenessModelImportDialogTest {

	private final LivenessTexts english = new LivenessTexts(ResourceBundle.getBundle("labels", Locale.ENGLISH), null);

	@Test
	public void everyStatusHasATextInEveryLanguage() {
		for (String lang : Arrays.asList("en", "fr", "ar")) {
			LivenessTexts texts = new LivenessTexts(ResourceBundle.getBundle("labels", new Locale(lang)), null);
			for (Status s : Status.values()) {
				assertTrue(lang + " " + s, texts.hasOwnText(s.messageKey()));
			}
			for (String key : Arrays.asList(LivenessModelImportDialog.TITLE, LivenessModelImportDialog.MENU,
					LivenessModelImportDialog.CURRENT, LivenessModelImportDialog.IMPORT,
					LivenessModelImportDialog.WORKING, LivenessModelImportDialog.PATH)) {
				assertTrue(lang + " " + key, texts.hasOwnText(key));
			}
		}
	}

	@Test
	public void resultTextsNameTheVersions() {
		assertEquals("Import refused: version 1.0.0 is not newer than version 1.1.0. Older models cannot be installed "
				+ "(rollback protection). The current models stay in use.",
				english.text(Status.TOO_OLD.messageKey(), "b", "1.0.0", "1.1.0"));
		assertEquals("Models mosip-liveness-models version 1.0.1 verified and now in use (previous version 1.0.0).",
				english.text(Status.IMPORTED.messageKey(), "mosip-liveness-models", "1.0.1", "1.0.0"));
	}

	@Test
	public void unknownRunningBundleHasItsOwnText() {
		assertEquals("The active liveness models could not be read.",
				LivenessModelImportDialog.currentText(Optional.empty(), english));
	}

	@Test
	public void modelLinesShowIdVersionAndHashPrefix() {
		String sha = "db4b768438ee1304d7e22b9cb8447360ba3750f133b377d68bdf484be2b3f886";
		assertEquals("facemesh  v2-fp16w  sha256:db4b7684",
				LivenessModelImportDialog.modelLines(Collections.singletonList(ModelInfo.of("facemesh", "v2-fp16w",
						sha))));
		assertEquals("", LivenessModelImportDialog.modelLines(Collections.<ModelInfo>emptyList()));
	}

	@Test
	public void onlySupervisorsAndAdministratorsMayImport() {
		assertTrue(LivenessModelImportDialog.mayImport(Arrays.asList("REGISTRATION_OFFICER", "REGISTRATION_SUPERVISOR")));
		assertTrue(LivenessModelImportDialog.mayImport(Collections.singletonList("REGISTRATION_ADMIN")));
		assertFalse(LivenessModelImportDialog.mayImport(Collections.singletonList("REGISTRATION_OFFICER")));
		assertFalse(LivenessModelImportDialog.mayImport(null));
	}
}
