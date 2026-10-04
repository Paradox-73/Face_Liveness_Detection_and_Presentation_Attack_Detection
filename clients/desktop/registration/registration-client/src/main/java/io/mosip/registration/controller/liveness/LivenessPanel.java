package io.mosip.registration.controller.liveness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/**
 * The reusable face liveness panel: live preview with an oval face guide, a prompt banner (icon, large text, guidance
 * lines), challenge progress, attempt counter, and Retry / Cancel / supervisor override actions.
 *
 * <p>The panel only draws; it holds no liveness logic. {@link LivenessDialog} feeds it {@link LivenessViewModel}s and
 * preview images. Every method must be called on the JavaFX application thread.
 *
 * <p>Layout and styles: {@code fxml/LivenessPanel.fxml} and {@code liveness-panel.css}. The preview and the icons keep
 * left-to-right orientation in right-to-left languages: the preview is mirrored like a selfie, and head-turn arrows
 * show the physical direction.
 *
 * <p>Under the prompt, a second, larger line shows the prompt in the resident's language when it differs from the
 * operator's ({@link LivenessViewModel#residentPrompt()}); it has its own text direction
 * ({@link #setResidentDirection(boolean)}).
 */
public class LivenessPanel extends VBox {

	private static final String FXML = "/fxml/LivenessPanel.fxml";
	private static final String CSS = "/liveness-panel.css";
	private static final String RESIDENT_PROMPT_STYLE = "liveness-resident-prompt";
	/** Larger than the 26 px operator prompt of {@code liveness-panel.css}, so it reads from the resident's seat. */
	private static final String RESIDENT_PROMPT_FONT = "-fx-font-size: 32px; -fx-font-weight: bold;";
	private static final String BULLET = "\u2022 ";
	private static final List<String> SEVERITY_STYLES = Arrays.asList(LivenessViewModel.STYLE_INFO,
			LivenessViewModel.STYLE_SUCCESS, LivenessViewModel.STYLE_WARNING, LivenessViewModel.STYLE_ERROR);

	@FXML
	private Label titleLabel;
	@FXML
	private Label offlineBadge;
	@FXML
	private StackPane previewPane;
	@FXML
	private ImageView previewImage;
	@FXML
	private Ellipse ovalGuide;
	@FXML
	private HBox promptBanner;
	@FXML
	private StackPane iconBox;
	@FXML
	private SVGPath promptIcon;
	@FXML
	private Label promptLabel;
	@FXML
	private VBox guidanceBox;
	@FXML
	private ProgressIndicator busyIndicator;
	@FXML
	private ProgressBar progressBar;
	@FXML
	private Label stepLabel;
	@FXML
	private Label attemptLabel;
	@FXML
	private Label diagnosticLabel;
	@FXML
	private Button overrideButton;
	@FXML
	private Button cancelButton;
	@FXML
	private Button retryButton;

	private final Label residentPromptLabel = new Label();
	private LivenessViewModel model;
	private Runnable retryAction = () -> {
	};
	private Runnable cancelAction = () -> {
	};
	private Runnable overrideAction = () -> {
	};

	/** Loads the panel layout. */
	public LivenessPanel() {
		FXMLLoader loader = new FXMLLoader(resource(FXML));
		loader.setRoot(this);
		loader.setController(this);
		try {
			loader.load();
		} catch (IOException e) {
			throw new UncheckedIOException("liveness panel layout could not be loaded", e);
		}
		getStylesheets().add(resource(CSS).toExternalForm());
		getStyleClass().add(LivenessViewModel.STYLE_INFO);
		promptIcon.setFillRule(FillRule.EVEN_ODD);
		previewImage.setScaleX(-1);
		previewPane.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
		iconBox.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
		promptLabel.setAccessibleRole(AccessibleRole.TEXT);
		addResidentPromptLine();
		setIcon(PromptIcon.FACE_OVAL);
	}

	/** Puts the resident's prompt line right under the operator's prompt, in the same banner column. */
	private void addResidentPromptLine() {
		residentPromptLabel.getStyleClass().addAll("liveness-prompt", RESIDENT_PROMPT_STYLE);
		residentPromptLabel.setStyle(RESIDENT_PROMPT_FONT);
		residentPromptLabel.setWrapText(true);
		residentPromptLabel.setMinHeight(Region.USE_PREF_SIZE);
		residentPromptLabel.setMaxWidth(Double.MAX_VALUE);
		residentPromptLabel.setAccessibleRole(AccessibleRole.TEXT);
		show(residentPromptLabel, false);
		Pane column = (Pane) promptLabel.getParent();
		column.getChildren().add(column.getChildren().indexOf(promptLabel) + 1, residentPromptLabel);
	}

	/**
	 * Sets the text direction of the resident's prompt line, which may differ from the operator's.
	 *
	 * @param rightToLeft {@code true} when the resident's language is written right to left
	 */
	public void setResidentDirection(boolean rightToLeft) {
		residentPromptLabel
				.setNodeOrientation(rightToLeft ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
	}

	/**
	 * Sets the button labels and the title.
	 *
	 * @param texts text resolver of the display language
	 * @param title title text (for example "Face verification" or "Supervisor verification")
	 */
	public void setTexts(LivenessTexts texts, String title) {
		titleLabel.setText(title);
		retryButton.setText(texts.text(LivenessTexts.UI_RETRY));
		cancelButton.setText(texts.text(LivenessTexts.UI_CANCEL));
		overrideButton.setText(texts.text(LivenessTexts.UI_OVERRIDE));
		previewPane.setAccessibleText(texts.text(LivenessTexts.UI_PREVIEW));
		offlineBadge.setText(texts.text(MessageKeys.OFFLINE_BADGE));
	}

	/**
	 * Draws a state.
	 *
	 * @param model what to show
	 */
	public void render(LivenessViewModel model) {
		this.model = model;
		getStyleClass().removeAll(SEVERITY_STYLES);
		getStyleClass().add(model.styleClass());
		setIcon(model.icon());
		promptLabel.setText(model.prompt());
		promptLabel.setAccessibleText(model.prompt());
		residentPromptLabel.setText(model.residentPrompt());
		residentPromptLabel.setAccessibleText(model.residentPrompt());
		show(residentPromptLabel, !model.residentPrompt().isEmpty());
		guidanceBox.getChildren().clear();
		for (String line : model.guidance()) {
			Label label = new Label(BULLET + line);
			label.setWrapText(true);
			label.setMinHeight(Region.USE_PREF_SIZE);
			guidanceBox.getChildren().add(label);
		}
		show(busyIndicator, model.isBusy());
		progressBar.setProgress(model.isTerminal() && !model.isRetryVisible() ? progressBar.getProgress()
				: model.progress());
		stepLabel.setText(model.stepText());
		attemptLabel.setText(model.attemptText());
		boolean retryWasVisible = retryButton.isVisible();
		show(retryButton, model.isRetryVisible());
		show(overrideButton, model.isOverrideVisible());
		if (model.isRetryVisible() && !retryWasVisible) {
			retryButton.requestFocus();
		}
	}

	/**
	 * Shows the latest preview frame.
	 *
	 * @param image decoded frame; {@code null} clears the preview
	 */
	public void showFrame(Image image) {
		previewImage.setImage(image);
	}

	/**
	 * Shows or hides the offline badge ("Offline - verification runs on this device").
	 *
	 * @param offline {@code true} when the client has no server connection
	 */
	public void setOffline(boolean offline) {
		show(offlineBadge, offline);
	}

	/**
	 * Shows technical details for supervisors when {@code mosip.registration.liveness.diagnostic.enable=Y}.
	 *
	 * @param text diagnostic text; {@code null} or empty hides the line
	 */
	public void setDiagnostic(String text) {
		diagnosticLabel.setText(text == null ? "" : text);
		show(diagnosticLabel, text != null && !text.isEmpty());
	}

	/**
	 * Changes the Cancel button text (Close once no further attempt is possible).
	 *
	 * @param text button text
	 */
	public void setCancelText(String text) {
		cancelButton.setText(text);
	}

	/**
	 * Sets the action of the Retry button.
	 *
	 * @param action the action
	 */
	public void setOnRetry(Runnable action) {
		this.retryAction = action;
	}

	/**
	 * Sets the action of the Cancel / Close button (also Escape).
	 *
	 * @param action the action
	 */
	public void setOnCancel(Runnable action) {
		this.cancelAction = action;
	}

	/**
	 * Sets the action of the supervisor override button.
	 *
	 * @param action the action
	 */
	public void setOnOverride(Runnable action) {
		this.overrideAction = action;
	}

	/**
	 * Returns the view model now shown (for tests and screenshot tools).
	 *
	 * @return the last rendered model, or {@code null} before the first render
	 */
	public LivenessViewModel currentModel() {
		return model;
	}

	/**
	 * Returns the prompt text now shown (for tests and screenshots).
	 *
	 * @return the prompt
	 */
	public String promptText() {
		return promptLabel.getText();
	}

	/**
	 * Returns the resident's prompt line now shown (for tests and screenshots).
	 *
	 * @return the text; empty when the line is hidden
	 */
	public String residentPromptText() {
		return residentPromptLabel.isVisible() ? residentPromptLabel.getText() : "";
	}

	/**
	 * Tells whether the Retry button is shown.
	 *
	 * @return {@code true} if visible
	 */
	public boolean isRetryShown() {
		return retryButton.isVisible();
	}

	@FXML
	private void onRetry() {
		show(retryButton, false);
		retryAction.run();
	}

	@FXML
	private void onCancel() {
		cancelAction.run();
	}

	@FXML
	private void onOverride() {
		overrideAction.run();
	}

	private void setIcon(PromptIcon icon) {
		promptIcon.setContent(LivenessIcons.path(icon));
	}

	private static void show(Node node, boolean visible) {
		node.setVisible(visible);
		node.setManaged(visible);
	}

	private static URL resource(String name) {
		URL url = LivenessPanel.class.getResource(name);
		if (url == null) {
			throw new IllegalStateException("missing resource " + name);
		}
		return url;
	}
}
