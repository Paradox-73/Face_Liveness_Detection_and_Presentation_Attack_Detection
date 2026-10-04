/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.service.bio.liveness.LivenessModelImporter;
import io.mosip.registration.service.bio.liveness.LivenessModelImporter.Result;
import io.mosip.registration.service.bio.liveness.LivenessModelImporter.Status;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Supervisor screen for the secure offline model update (bonus B6): shows the running liveness model bundle, lets a
 * supervisor pick a signed bundle (a folder or a {@code .zip} file, for example on a USB stick) and runs
 * {@link LivenessModelImporter}, which verifies the signature, the model hashes and the version (anti-rollback)
 * before switching the engine. The result, including a refusal, is shown in the window; the running models stay
 * active unless the new bundle passed every check.
 *
 * <p>Opened from the header menu item "Import liveness models", which only supervisors and administrators see
 * ({@link #addMenuItem(Menu, LivenessModelImporter)}).
 */
public final class LivenessModelImportDialog {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessModelImportDialog.class);

	/** Label keys (labels bundles). */
	static final String TITLE = "liveness.models.title";
	static final String MENU = "liveness.models.menu";
	static final String CURRENT = "liveness.models.current";
	static final String CURRENT_BUILTIN = "liveness.models.builtin";
	static final String CURRENT_IMPORTED = "liveness.models.imported";
	static final String CURRENT_UNKNOWN = "liveness.models.unknown";
	static final String PATH = "liveness.models.path";
	static final String BROWSE_FOLDER = "liveness.models.browse_folder";
	static final String BROWSE_ZIP = "liveness.models.browse_zip";
	static final String IMPORT = "liveness.models.import";
	static final String WORKING = "liveness.models.working";

	private static final String CSS = "/liveness-panel.css";
	private static final String NONE = "-";

	private final LivenessModelImporter importer;
	private final LivenessTexts texts;
	private final Stage stage = new Stage();
	private final Label current = new Label();
	private final TextField path = new TextField();
	private final Button importButton = new Button();
	private final ProgressIndicator busy = new ProgressIndicator();
	private final Label result = new Label();
	private final Label models = new Label();

	private LivenessModelImportDialog(LivenessModelImporter importer, LivenessTexts texts) {
		this.importer = Objects.requireNonNull(importer, "importer");
		this.texts = Objects.requireNonNull(texts, "texts");
	}

	/**
	 * Adds "Import liveness models" at the top of a menu when the logged-in user is a supervisor or administrator.
	 *
	 * @param menu     the header menu
	 * @param importer the import service; nothing is added when {@code null}
	 */
	public static void addMenuItem(Menu menu, LivenessModelImporter importer) {
		if (menu == null || importer == null || !mayImport(userRoles())) {
			return;
		}
		LivenessTexts texts = LivenessDialog.textsFor(ApplicationContext.applicationLanguage());
		MenuItem item = new MenuItem(texts.text(MENU));
		item.setId("livenessModelsMenu");
		item.setOnAction(event -> open(importer, item.getParentPopup() != null
				? item.getParentPopup().getOwnerWindow() : null));
		menu.getItems().add(0, new SeparatorMenuItem());
		menu.getItems().add(0, item);
	}

	/**
	 * Opens the window.
	 *
	 * @param importer the import service
	 * @param owner    owner window, or {@code null}
	 * @return the dialog
	 */
	public static LivenessModelImportDialog open(LivenessModelImporter importer, Window owner) {
		LivenessModelImportDialog dialog = new LivenessModelImportDialog(importer,
				LivenessDialog.textsFor(ApplicationContext.applicationLanguage()));
		dialog.show(owner);
		return dialog;
	}

	/**
	 * Tells whether a user with these roles may import models (the service checks again).
	 *
	 * @param roles the user's roles
	 * @return {@code true} for a supervisor or administrator
	 */
	static boolean mayImport(List<String> roles) {
		return roles != null && (roles.contains("REGISTRATION_SUPERVISOR") || roles.contains("REGISTRATION_ADMIN"));
	}

	/**
	 * Returns the text shown for an import result.
	 *
	 * @param r     the result
	 * @param texts text resolver
	 * @return the message
	 */
	static String resultText(Result r, LivenessTexts texts) {
		return texts.text(r.status().messageKey(), orNone(r.bundle()), orNone(r.version()),
				orNone(r.previousVersion()));
	}

	/**
	 * Returns the description of the running bundle.
	 *
	 * @param running the running bundle, if it could be read
	 * @param texts   text resolver
	 * @return the text
	 */
	static String currentText(Optional<Result> running, LivenessTexts texts) {
		if (!running.isPresent()) {
			return texts.text(CURRENT_UNKNOWN);
		}
		Result r = running.get();
		return texts.text(CURRENT, r.bundle(), r.version(),
				texts.text(r.isFromStore() ? CURRENT_IMPORTED : CURRENT_BUILTIN));
	}

	/**
	 * Lists the models of a bundle, one per line: identifier, version and the start of the SHA-256.
	 *
	 * @param list the models
	 * @return the lines, empty for no model
	 */
	static String modelLines(List<ModelInfo> list) {
		StringBuilder sb = new StringBuilder();
		for (ModelInfo m : list) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(m.id()).append("  ").append(m.version()).append("  sha256:").append(m.sha256Prefix());
		}
		return sb.toString();
	}

	private void show(Window owner) {
		Label title = new Label(texts.text(TITLE));
		title.getStyleClass().add("liveness-title");
		current.setId("livenessModelCurrent");
		current.setWrapText(true);
		path.setId("livenessModelPath");
		path.setPromptText(texts.text(PATH));
		HBox.setHgrow(path, Priority.ALWAYS);
		Button folder = new Button(texts.text(BROWSE_FOLDER));
		folder.setId("livenessModelBrowseFolder");
		folder.getStyleClass().add("liveness-secondary-button");
		folder.setOnAction(e -> chooseFolder());
		Button zip = new Button(texts.text(BROWSE_ZIP));
		zip.setId("livenessModelBrowseZip");
		zip.getStyleClass().add("liveness-secondary-button");
		zip.setOnAction(e -> chooseZip());
		HBox pathRow = new HBox(8, path, folder, zip);
		pathRow.setAlignment(Pos.CENTER_LEFT);

		importButton.setId("livenessModelImport");
		importButton.setText(texts.text(IMPORT));
		importButton.getStyleClass().add("liveness-primary-button");
		importButton.setDefaultButton(true);
		importButton.setOnAction(e -> runImport());
		busy.setVisible(false);
		busy.setMaxSize(28, 28);
		Button close = new Button(texts.text(LivenessTexts.UI_CLOSE));
		close.setId("livenessModelClose");
		close.getStyleClass().add("liveness-secondary-button");
		close.setCancelButton(true);
		close.setOnAction(e -> stage.close());
		HBox actions = new HBox(12, importButton, busy, close);
		actions.setAlignment(Pos.CENTER_LEFT);

		result.setId("livenessModelResult");
		result.setWrapText(true);
		result.setVisible(false);
		models.setId("livenessModelList");
		models.getStyleClass().add("liveness-diagnostic");
		models.setVisible(false);

		VBox root = new VBox(12, title, current, pathRow, actions, result, models);
		root.setPadding(new Insets(20));
		root.setPrefWidth(680);
		root.getStyleClass().add("liveness-panel");
		URL css = LivenessModelImportDialog.class.getResource(CSS);
		Scene scene = new Scene(root);
		if (css != null) {
			scene.getStylesheets().add(css.toExternalForm());
		}
		stage.setTitle(texts.text(TITLE));
		stage.setScene(scene);
		if (owner != null) {
			stage.initOwner(owner);
			stage.initModality(Modality.WINDOW_MODAL);
		}
		showCurrent();
		stage.show();
	}

	private void showCurrent() {
		Optional<Result> running = importer.current();
		current.setText(currentText(running, texts));
		if (running.isPresent()) {
			showModels(running.get().models());
		}
	}

	private void chooseFolder() {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle(texts.text(TITLE));
		File dir = chooser.showDialog(stage);
		if (dir != null) {
			path.setText(dir.getAbsolutePath());
		}
	}

	private void chooseZip() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(texts.text(TITLE));
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
		File file = chooser.showOpenDialog(stage);
		if (file != null) {
			path.setText(file.getAbsolutePath());
		}
	}

	private void runImport() {
		String text = path.getText() == null ? "" : path.getText().trim();
		if (text.isEmpty()) {
			return;
		}
		Path location = Paths.get(text);
		String userId = SessionContext.isSessionContextAvailable() ? SessionContext.userId() : "";
		List<String> roles = userRoles();
		importButton.setDisable(true);
		busy.setVisible(true);
		showResult(texts.text(WORKING), null);
		Thread worker = new Thread(() -> {
			Result r;
			try {
				r = importer.importBundle(location, userId, roles);
			} catch (RuntimeException e) {
				LOGGER.error("Liveness model import failed: {}", e.getClass().getSimpleName());
				r = null;
			}
			Result done = r;
			Platform.runLater(() -> finished(done));
		}, "liveness-model-import");
		worker.setDaemon(true);
		worker.start();
	}

	private void finished(Result r) {
		busy.setVisible(false);
		importButton.setDisable(false);
		if (r == null) {
			showResult(texts.text(Status.LOAD_FAILED.messageKey(), NONE, NONE, NONE), false);
			return;
		}
		showResult(resultText(r, texts), r.isImported());
		if (r.isImported()) {
			showCurrent();
		}
	}

	private void showResult(String message, Boolean ok) {
		result.setText(message);
		result.setVisible(true);
		result.getStyleClass().removeAll("liveness-models-ok", "liveness-models-error");
		if (ok != null) {
			result.getStyleClass().add(ok ? "liveness-models-ok" : "liveness-models-error");
		}
		stage.sizeToScene();
	}

	private void showModels(List<ModelInfo> list) {
		models.setText(modelLines(list));
		models.setVisible(!list.isEmpty());
		stage.sizeToScene();
	}

	private static List<String> userRoles() {
		try {
			return SessionContext.isSessionContextAvailable() && SessionContext.userContext().getRoles() != null
					? SessionContext.userContext().getRoles() : Collections.<String>emptyList();
		} catch (RuntimeException e) {
			return Collections.emptyList();
		}
	}

	private static String orNone(String s) {
		return s == null || s.isEmpty() ? NONE : s;
	}
}
