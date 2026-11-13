import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.transform.Shear;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EncryptedDiaryApp extends Application {

    // ==========================================
    // MODULAR FONT CONFIGURATION (.ttf files)
    // ==========================================
    private static final String UI_FONT_PATH = "/LiberationSans.ttf";
    private static final String ENTRY_FONT_PATH = "/TimesNewRoman.ttf";

    private String uiFontFamily = "sans-serif";
    private String entryFontFamily = "serif";

    // Application Persistence Constants
    private static final String VAULT_FILE = "diary_vault.enc";
    private static final String SPECIAL_ENTRY_ID = "__SPECIAL_VAULT_ITEM__";
    private static final int SALT_SIZE = 16;
    private static final int IV_SIZE = 12;
    private static final int KEY_LENGTH = 256;
    private static final int ITERATIONS = 100000;

    private Stage primaryStage;
    private SecretKey derivedKey;
    private byte[] currentSalt;
    private List<JournalEntry> entries = new ArrayList<>();
    private JournalEntry currentEntry = null;
    private JournalEntry specialEntry = null;
    private boolean isSpecialMode = false;

    // UI Components
    private Label datePickerLabel;
    private DatePicker datePicker;
    private TextField titleInput;
    private TextArea contentInput;
    private Label statusLabel;
    private Button deleteBtn;
    private Button specialMenuBtn;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Encrypted Journal");

        // Load Window & Taskbar Icon
        try (InputStream iconStream = getClass().getResourceAsStream("/icon.png")) {
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception ignored) {}

        // Initialize dynamic font loading
        initializeFonts();

        showAuthScreen();
        primaryStage.show();
        primaryStage.setMaximized(true);
    }
    

    // ==========================================
    // DYNAMIC FONT LOADER
    // ==========================================
    private void initializeFonts() {
        uiFontFamily = loadFontFamily(UI_FONT_PATH, "sans-serif");
        entryFontFamily = loadFontFamily(ENTRY_FONT_PATH, "serif");
    }

    private String loadFontFamily(String resourcePath, String fallback) {
        try (InputStream fontStream = getClass().getResourceAsStream(resourcePath)) {
            if (fontStream != null) {
                Font loadedFont = Font.loadFont(fontStream, 14);
                if (loadedFont != null) {
                    return "'" + loadedFont.getFamily() + "'";
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    // Swaps root node on the active Scene to prevent window restore down
    private void setStageRoot(Parent root) {
        Scene scene = primaryStage.getScene();
        if (scene == null) {
            scene = new Scene(root, 960, 640);
            applyGlobalStyles(scene);
            primaryStage.setScene(scene);
        } else {
            scene.setRoot(root);
        }
        primaryStage.setMaximized(true);
    }

    // ==========================================
    // DYNAMIC BACKGROUND HELPER WITH DARK FILTER
    // ==========================================
    private ImageView createBackgroundView(StackPane root) {
        ImageView bgView = new ImageView();
        try {
            InputStream is = getClass().getResourceAsStream("/background.jpg");
            if (is != null) {
                Image img = new Image(is);
                bgView.setImage(img);
                bgView.setPreserveRatio(false);
                bgView.fitWidthProperty().bind(root.widthProperty());
                bgView.fitHeightProperty().bind(root.heightProperty());

                // Apply dynamic dark filter using ColorAdjust (-1.0 is pitch black, 0.0 is original)
                ColorAdjust darkFilter = new ColorAdjust();
                darkFilter.setBrightness(-0.55); // Reduce brightness by 55%
                darkFilter.setContrast(0.15);    // Slightly boost contrast for richer shadows
                bgView.setEffect(darkFilter);
            }
        } catch (Exception ignored) {}
        return bgView;
    }

    // ==========================================
    // AUTHENTICATION & LOCK SCREEN
    // ==========================================
    private void showAuthScreen() {
        boolean vaultExists = new File(VAULT_FILE).exists();

        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #040e14;");

        ImageView bgView = createBackgroundView(root);

        VBox card = new VBox(18);
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(380);
        card.setPadding(new Insets(35));
        card.setStyle(getGlassCardStyle());

        Label iconLabel = new Label("🌲");
        iconLabel.setStyle("-fx-font-size: 42px;");

        Label titleLabel = new Label(vaultExists ? "UNLOCK VAULT" : "CREATE VAULT");
        titleLabel.setStyle(getSpaceTitleStyle(22));

        Label subtitleLabel = new Label(vaultExists ? 
            "Enter master key to decrypt cosmos vault." : 
            "Set master password. Lost keys cannot be recovered.");
        subtitleLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-alignment: center;");
        subtitleLabel.setWrapText(true);

        PasswordField passwordBox = new PasswordField();
        passwordBox.setPromptText("Master Password");
        passwordBox.setMaxWidth(300);
        passwordBox.setStyle(getInputStyle());

        PasswordField confirmPasswordBox = new PasswordField();
        confirmPasswordBox.setPromptText("Confirm Password");
        confirmPasswordBox.setMaxWidth(300);
        confirmPasswordBox.setStyle(getInputStyle());
        confirmPasswordBox.setManaged(!vaultExists);
        confirmPasswordBox.setVisible(!vaultExists);

        Button actionBtn = new Button(vaultExists ? "UNLOCK" : "INITIALIZE");
        actionBtn.setMaxWidth(300);
        actionBtn.setStyle(getPrimaryButtonStyle());

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #ff4757; -fx-font-size: 12px; -fx-font-weight: bold;");

        actionBtn.setOnAction(e -> {
            String pass = passwordBox.getText();
            if (pass.isEmpty()) {
                errorLabel.setText("Password cannot be empty.");
                return;
            }

            if (!vaultExists) {
                if (!pass.equals(confirmPasswordBox.getText())) {
                    errorLabel.setText("Passwords do not match.");
                    return;
                }
                try {
                    initializeNewVault(pass);
                    showMainScreen();
                } catch (Exception ex) {
                    errorLabel.setText("Failed to create vault: " + ex.getMessage());
                }
            } else {
                try {
                    if (unlockVault(pass)) {
                        showMainScreen();
                    } else {
                        errorLabel.setText("Incorrect key or corrupted vault.");
                    }
                } catch (Exception ex) {
                    errorLabel.setText("Decryption error: Invalid key.");
                }
            }
        });

        card.getChildren().addAll(iconLabel, titleLabel, subtitleLabel, passwordBox, confirmPasswordBox, actionBtn, errorLabel);
        root.getChildren().addAll(bgView, card);

        setStageRoot(root);
    }

    // ==========================================
    // MAIN DIARY INTERFACE
    // ==========================================
    private void showMainScreen() {
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #040e14;");

        ImageView bgView = createBackgroundView(root);

        // Main Container (Centered Focus)
        VBox mainContainer = new VBox(12);
        mainContainer.setPadding(new Insets(24, 50, 20, 50));
        mainContainer.setAlignment(Pos.CENTER);

        // --- ENTRY TITLE (CUSTOM TTF + SHEAR ITALIC + BLACK SHADOW) ---
        titleInput = new TextField();
        titleInput.setPromptText("Entry Title...");
        titleInput.setStyle(
            "-fx-font-family: " + entryFontFamily + "; " +
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #f1f5f9; " +
            "-fx-font-size: 24px; " +
            "-fx-border-width: 0; " +
            "-fx-border-color: transparent; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent; " +
            "-fx-padding: 2 0 4 0; " +
            "-fx-effect: dropshadow(three-pass-box, #000000, 8, 0.5, 2, 2);"
        );
        // Force slant transformation to produce italics on custom TTF files
        titleInput.getTransforms().add(new Shear(-0.2, 0.0));

        // --- MAIN TEXT EDITOR AREA (CUSTOM TTF + SHEAR ITALIC + BLACK SHADOW) ---
        contentInput = new TextArea();
        contentInput.setPromptText("Log cosmic entry details...");
        contentInput.setWrapText(true);
        // Force slant transformation to produce italics on custom TTF files
        contentInput.getTransforms().add(new Shear(-0.2, 0.0));

        Pane transparentEditorBox = createTransparentEditorContainer(contentInput);
        VBox.setVgrow(transparentEditorBox, Priority.ALWAYS);

        // --- BOTTOM ACTION & NAVIGATION BAR ---
        HBox bottomBar = new HBox(12);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(8, 14, 8, 14));
        bottomBar.setStyle("-fx-background-color: rgba(6, 20, 27, 0.85); -fx-background-radius: 8; -fx-border-color: rgba(0, 240, 255, 0.3); -fx-border-radius: 8;");

        datePickerLabel = new Label("DATE:");
        datePickerLabel.setStyle(
            "-fx-font-family: " + uiFontFamily + "; " +
            "-fx-text-fill: #00f0ff; " +
            "-fx-font-weight: bold; " +
            "-fx-font-size: 10px;"
        );

        datePicker = new DatePicker(LocalDate.now());
        datePicker.setStyle(getSlimDatePickerStyle());
        datePicker.valueProperty().addListener((obs, oldDate, newDate) -> {
            if (newDate != null) {
                loadOrCreateEntryForDate(newDate);
            }
        });

        // Setup calendar cell factory & animation triggers
        refreshDatePickerCells();

        Button lockBtn = new Button("🔒 LOCK");
        lockBtn.setStyle(getSlimSecondaryButtonStyle());
        lockBtn.setOnAction(e -> lockDiary());

        // Heart button for SPECIAL long-term storage section
        specialMenuBtn = new Button("SPECIAL");
        specialMenuBtn.setStyle(getSpecialButtonStyle(false));
        specialMenuBtn.setOnAction(e -> toggleSpecialSection());

        statusLabel = new Label();
        statusLabel.setStyle(
            "-fx-font-family: " + uiFontFamily + "; " +
            "-fx-text-fill: #ff9e3b; " +
            "-fx-font-size: 11px; " +
            "-fx-font-weight: bold;"
        );
        HBox.setMargin(statusLabel, new Insets(0, 0, 0, 10));

        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);

        deleteBtn = new Button("PURGE");
        deleteBtn.setStyle(getDangerButtonStyle());
        deleteBtn.setOnAction(e -> deleteCurrentEntry());
        HBox.setMargin(deleteBtn, new Insets(0, 6, 0, 6));

        Button saveBtn = new Button("ENCRYPT & SAVE");
        saveBtn.setStyle(getPrimaryButtonStyle());
        saveBtn.setOnAction(e -> saveCurrentEntry());

        bottomBar.getChildren().addAll(datePickerLabel, datePicker, lockBtn, specialMenuBtn, statusLabel, actionSpacer, deleteBtn, saveBtn);

        mainContainer.getChildren().addAll(titleInput, transparentEditorBox, bottomBar);
        root.getChildren().addAll(bgView, mainContainer);

        // Load entry for today's date
        loadOrCreateEntryForDate(datePicker.getValue());

        setStageRoot(root);
    }

    private Pane createTransparentEditorContainer(TextArea textArea) {
        StackPane container = new StackPane();
        container.setPadding(new Insets(4));

        textArea.setStyle(
            "-fx-font-family: " + entryFontFamily + "; " +
            "-fx-background-color: transparent; " +
            "-fx-control-inner-background: transparent; " +
            "-fx-text-fill: #ffffff; " +
            "-fx-font-size: 21px; " +
            "-fx-border-width: 0; " +
            "-fx-border-color: transparent; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent; " +
            "-fx-padding: 6; " +
            "-fx-effect: dropshadow(three-pass-box, #000000, 8, 0.5, 1, 2);"
        );

        container.getChildren().add(textArea);
        return container;
    }

    // ==========================================
    // INSTANT-REFRESH HIGH-CONTRAST CALENDAR
    // ==========================================
    private boolean hasEntryForDate(LocalDate date) {
        if (date == null) return false;
        String datePrefix = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        for (JournalEntry entry : entries) {
            if (SPECIAL_ENTRY_ID.equals(entry.getId())) continue;
            if (entry.getDateTime() != null && entry.getDateTime().startsWith(datePrefix)) {
                boolean hasTitle = entry.getTitle() != null && !entry.getTitle().trim().isEmpty();
                boolean hasContent = entry.getContent() != null && !entry.getContent().trim().isEmpty();
                if (hasTitle || hasContent) {
                    return true;
                }
            }
        }
        return false;
    }

    private void refreshDatePickerCells() {
        if (datePicker == null) return;

        // --- WINDOWS TASKBAR FLYOUT ANIMATION ON POPUP OPEN ---
        datePicker.setOnShowing(e -> Platform.runLater(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof PopupWindow) {
                    Scene popupScene = window.getScene();
                    if (popupScene != null && popupScene.getRoot() != null) {
                        Node rootNode = popupScene.getRoot();
                        rootNode.setOpacity(0.0);
                        rootNode.setTranslateY(10);

                        FadeTransition fadeIn = new FadeTransition(Duration.millis(160), rootNode);
                        fadeIn.setToValue(1.0);

                        TranslateTransition slideUp = new TranslateTransition(Duration.millis(160), rootNode);
                        slideUp.setToY(0);

                        ParallelTransition flyoutAnimation = new ParallelTransition(fadeIn, slideUp);
                        flyoutAnimation.play();
                    }
                }
            }
        }));

        // --- REAL-TIME RESPONSIVE DAY CELL FACTORY ---
        datePicker.setDayCellFactory(picker -> new DateCell() {
            private final ScaleTransition scaleUp = new ScaleTransition(Duration.millis(100), this);
            private final ScaleTransition scaleDown = new ScaleTransition(Duration.millis(100), this);

            {
                scaleUp.setToX(1.1);
                scaleUp.setToY(1.1);
                scaleDown.setToX(1.0);
                scaleDown.setToY(1.0);

                setOnMouseEntered(ev -> {
                    if (!isEmpty() && getItem() != null) {
                        scaleDown.stop();
                        scaleUp.playFromStart();
                    }
                });

                setOnMouseExited(ev -> {
                    if (!isEmpty() && getItem() != null) {
                        scaleUp.stop();
                        scaleDown.playFromStart();
                    }
                });

                // LISTEN FOR REAL-TIME SELECTION CHANGES WHILE POPUP IS OPEN
                datePicker.valueProperty().addListener((obs, oldVal, newVal) -> updateCellStyle());
                selectedProperty().addListener((obs, oldVal, newVal) -> updateCellStyle());
            }

            private void updateCellStyle() {
                LocalDate item = getItem();
                if (isEmpty() || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                    return;
                }

                boolean isSelected = item.equals(datePicker.getValue());
                boolean hasEntry = hasEntryForDate(item);
                boolean isToday = item.equals(LocalDate.now());
                boolean isCurrentMonth = datePicker.getValue() != null && item.getMonth() == datePicker.getValue().getMonth();

                StringBuilder css = new StringBuilder();
                css.append("-fx-background-radius: 4px; -fx-alignment: center; -fx-font-size: 12px; ");

                if (isSelected) {
                    css.append("-fx-background-color: #ffffff !important; ")
                       .append("-fx-text-fill: #000000 !important; ")
                       .append("-fx-font-weight: bold; ")
                       .append("-fx-border-color: #00f0ff; ")
                       .append("-fx-border-width: 2px; ");
                } else if (hasEntry) {
                    css.append("-fx-background-color: #00f0ff; ")
                       .append("-fx-text-fill: #000000 !important; ")
                       .append("-fx-font-weight: bold; ")
                       .append("-fx-border-color: #ffffff; ")
                       .append("-fx-border-width: 1px; ");
                } else if (!isCurrentMonth) {
                    css.append("-fx-background-color: #161616; ")
                       .append("-fx-text-fill: #666666 !important; ");
                } else if (isToday) {
                    css.append("-fx-background-color: #333333; ")
                       .append("-fx-text-fill: #ffffff !important; ")
                       .append("-fx-font-weight: bold; ")
                       .append("-fx-border-color: #00f0ff; ")
                       .append("-fx-border-width: 2px; ");
                } else {
                    css.append("-fx-background-color: #262626; ")
                       .append("-fx-text-fill: #ffffff !important; ");
                }

                setStyle(css.toString());
            }

            @Override
            public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                updateCellStyle();
            }
        });
    }

    // ==========================================
    // APP LOGIC & STATE MANAGEMENT
    // ==========================================
    private void toggleSpecialSection() {
        if (isSpecialMode) {
            isSpecialMode = false;
            specialMenuBtn.setStyle(getSpecialButtonStyle(false));
            setDailyControlsVisible(true);
            if (datePicker != null && datePicker.getValue() != null) {
                loadOrCreateEntryForDate(datePicker.getValue());
            }
        } else {
            openSpecialSection();
        }
    }

    private void openSpecialSection() {
        isSpecialMode = true;
        specialMenuBtn.setStyle(getSpecialButtonStyle(true));
        setDailyControlsVisible(false);

        if (specialEntry == null) {
            specialEntry = new JournalEntry(
                SPECIAL_ENTRY_ID,
                "Long-Term Sanctuary",
                "",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            );
        }

        titleInput.setText(specialEntry.getTitle());
        titleInput.setPromptText("Long-Term Entry Title...");
        contentInput.setText(specialEntry.getContent());
        contentInput.setPromptText("Store long-term memories, reflections, or keepsakes here...");
        statusLabel.setText("SPECIAL ARCHIVE ACTIVE");
    }

    private void setDailyControlsVisible(boolean visible) {
        if (datePickerLabel != null) {
            datePickerLabel.setVisible(visible);
            datePickerLabel.setManaged(visible);
        }
        if (datePicker != null) {
            datePicker.setVisible(visible);
            datePicker.setManaged(visible);
        }
        if (deleteBtn != null) {
            deleteBtn.setVisible(visible);
            deleteBtn.setManaged(visible);
        }
    }

    private void loadOrCreateEntryForDate(LocalDate date) {
        if (isSpecialMode) {
            isSpecialMode = false;
            if (specialMenuBtn != null) {
                specialMenuBtn.setStyle(getSpecialButtonStyle(false));
            }
            setDailyControlsVisible(true);
        }

        String datePrefix = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        JournalEntry existing = null;
        for (JournalEntry entry : entries) {
            if (entry.getDateTime() != null && entry.getDateTime().startsWith(datePrefix)) {
                existing = entry;
                break;
            }
        }

        titleInput.setPromptText("Entry Title...");
        contentInput.setPromptText("Log cosmic entry details...");

        if (existing != null) {
            currentEntry = existing;
            titleInput.setText(currentEntry.getTitle());
            contentInput.setText(currentEntry.getContent());
            statusLabel.setText("LOG LOADED FOR " + datePrefix);
        } else {
            currentEntry = new JournalEntry(
                UUID.randomUUID().toString(),
                "",
                "",
                datePrefix + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            );
            entries.add(0, currentEntry);
            titleInput.setText("");
            contentInput.setText("");
            statusLabel.setText("NEW ENTRY CREATED FOR " + datePrefix);
        }
    }

    private void saveCurrentEntry() {
        if (isSpecialMode && specialEntry != null) {
            specialEntry.setTitle(titleInput.getText());
            specialEntry.setContent(contentInput.getText());
            try {
                saveVault();
                statusLabel.setText("SPECIAL ARCHIVE SEALED & ENCRYPTED!");
            } catch (Exception e) {
                statusLabel.setText("SAVE FAILED!");
            }
            return;
        }

        if (currentEntry != null) {
            currentEntry.setTitle(titleInput.getText());
            currentEntry.setContent(contentInput.getText());
            try {
                saveVault();
                refreshDatePickerCells();
                statusLabel.setText("SEALED & ENCRYPTED!");
            } catch (Exception e) {
                statusLabel.setText("SAVE FAILED!");
            }
        }
    }

    private void deleteCurrentEntry() {
        if (!isSpecialMode && currentEntry != null) {
            entries.remove(currentEntry);
            try {
                saveVault();
                refreshDatePickerCells();
                if (datePicker != null && datePicker.getValue() != null) {
                    loadOrCreateEntryForDate(datePicker.getValue());
                }
                statusLabel.setText("ENTRY PURGED.");
            } catch (Exception e) {
                statusLabel.setText("DELETE FAILED!");
            }
        }
    }

    private void lockDiary() {
        derivedKey = null;
        currentSalt = null;
        entries.clear();
        currentEntry = null;
        specialEntry = null;
        isSpecialMode = false;
        showAuthScreen();
    }

    // ==========================================
    // CRYPTOGRAPHY & PERSISTENCE
    // ==========================================
    private void initializeNewVault(String password) throws Exception {
        this.currentSalt = new byte[SALT_SIZE];
        new SecureRandom().nextBytes(this.currentSalt);
        this.derivedKey = deriveKey(password, this.currentSalt);
        this.entries = new ArrayList<>();
        this.specialEntry = null;
        
        saveVaultWithSalt(this.currentSalt);
    }

    private boolean unlockVault(String password) throws Exception {
        File file = new File(VAULT_FILE);
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            byte[] salt = new byte[SALT_SIZE];
            dis.readFully(salt);

            byte[] iv = new byte[IV_SIZE];
            dis.readFully(iv);

            byte[] cipherText = dis.readAllBytes();

            SecretKey key = deriveKey(password, salt);
            String jsonPayload = decrypt(cipherText, key, iv);

            this.currentSalt = salt;
            this.derivedKey = key;
            this.specialEntry = null;

            List<JournalEntry> loaded = deserializeEntries(jsonPayload);
            List<JournalEntry> regularEntries = new ArrayList<>();
            for (JournalEntry item : loaded) {
                if (SPECIAL_ENTRY_ID.equals(item.getId())) {
                    this.specialEntry = item;
                } else {
                    regularEntries.add(item);
                }
            }
            this.entries = regularEntries;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveVault() throws Exception {
        if (this.currentSalt == null || this.derivedKey == null) {
            throw new IllegalStateException("Vault is locked or uninitialized.");
        }
        saveVaultWithSalt(this.currentSalt);
    }

    private void saveVaultWithSalt(byte[] salt) throws Exception {
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);

        List<JournalEntry> allToSave = new ArrayList<>(entries);
        if (specialEntry != null) {
            allToSave.add(specialEntry);
        }

        String payload = serializeEntries(allToSave);
        byte[] cipherText = encrypt(payload, derivedKey, iv);

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(VAULT_FILE))) {
            dos.write(salt);
            dos.write(iv);
            dos.write(cipherText);
        }
    }

    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private byte[] encrypt(String plaintext, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    private String decrypt(byte[] ciphertext, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] plainBytes = cipher.doFinal(ciphertext);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    // --- JSON SERIALIZATION ---
    private String serializeEntries(List<JournalEntry> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i).toJson());
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private List<JournalEntry> deserializeEntries(String raw) {
        List<JournalEntry> result = new ArrayList<>();
        if (raw == null || raw.trim().equals("[]")) return result;
        
        String clean = raw.trim().substring(1, raw.length() - 1);
        String[] objects = clean.split("(?<=\\}),\\s*(?=\\{)");
        for (String obj : objects) {
            if (!obj.isBlank()) {
                result.add(JournalEntry.fromJson(obj));
            }
        }
        return result;
    }

    // ==========================================
    // STYLES & THEME SYSTEM
    // ==========================================
    private String getSpaceTitleStyle(int size) {
        return "-fx-font-family: " + uiFontFamily + "; " +
               "-fx-font-size: " + size + "px; " +
               "-fx-text-fill: #ffffff;";
    }

    private String getGlassCardStyle() {
        return "-fx-background-color: rgba(6, 20, 27, 0.85); " +
               "-fx-border-color: rgba(0, 240, 255, 0.35); " +
               "-fx-border-width: 1px; " +
               "-fx-background-radius: 12px; " +
               "-fx-border-radius: 12px;";
    }

    private String getInputStyle() {
        return "-fx-background-color: rgba(4, 14, 20, 0.9); " +
               "-fx-text-fill: #00f0ff; " +
               "-fx-prompt-text-fill: #4e6185; " +
               "-fx-padding: 10; " +
               "-fx-background-radius: 6; " +
               "-fx-border-color: rgba(0, 240, 255, 0.3); " +
               "-fx-border-radius: 6;";
    }

    private String getSlimDatePickerStyle() {
        return "-fx-background-color: rgba(4, 14, 20, 0.85); " +
               "-fx-border-color: rgba(0, 240, 255, 0.5); " +
               "-fx-border-radius: 4; " +
               "-fx-background-radius: 4; " +
               "-fx-pref-height: 26px; " +
               "-fx-cursor: hand;";
    }

    private String getPrimaryButtonStyle() {
        return "-fx-font-family: " + uiFontFamily + "; " +
               "-fx-background-color: rgba(0, 240, 255, 0.18); " +
               "-fx-text-fill: #00f0ff; " +
               "-fx-border-color: #00f0ff; " +
               "-fx-border-width: 1.5px; " +
               "-fx-padding: 6 14 6 14; " +
               "-fx-background-radius: 6; " +
               "-fx-border-radius: 6; " +
               "-fx-cursor: hand;";
    }

    private String getSlimSecondaryButtonStyle() {
        return "-fx-font-family: " + uiFontFamily + "; " +
               "-fx-background-color: rgba(6, 20, 27, 0.7); " +
               "-fx-text-fill: #94a3b8; " +
               "-fx-border-color: rgba(148, 163, 184, 0.3); " +
               "-fx-border-width: 1px; " +
               "-fx-padding: 4 10 4 10; " +
               "-fx-font-size: 10px; " +
               "-fx-background-radius: 4; " +
               "-fx-border-radius: 4; " +
               "-fx-cursor: hand;";
    }

    private String getSpecialButtonStyle(boolean active) {
        if (active) {
            return "-fx-background-color: rgba(255, 0, 127, 0.32); " +
                   "-fx-text-fill: #ff007f; " +
                   "-fx-border-color: #ff007f; " +
                   "-fx-border-width: 1.5px; " +
                   "-fx-padding: 3 10 3 10; " +
                   "-fx-font-size: 11px; " +
                   "-fx-background-radius: 4; " +
                   "-fx-border-radius: 4; " +
                   "-fx-cursor: hand; " +
                   "-fx-effect: dropshadow(three-pass-box, rgba(255, 0, 127, 0.8), 10, 0.4, 0, 0);";
        }
        return "-fx-background-color: rgba(255, 0, 127, 0.12); " +
               "-fx-text-fill: #ff007f; " +
               "-fx-border-color: rgba(255, 0, 127, 0.45); " +
               "-fx-border-width: 1px; " +
               "-fx-padding: 3 10 3 10; " +
               "-fx-font-size: 11px; " +
               "-fx-background-radius: 4; " +
               "-fx-border-radius: 4; " +
               "-fx-cursor: hand;";
    }

    private String getDangerButtonStyle() {
        return "-fx-font-family: " + uiFontFamily + "; " +
               "-fx-background-color: rgba(255, 71, 87, 0.18); " +
               "-fx-text-fill: #ff4757; " +
               "-fx-border-color: #ff4757; " +
               "-fx-border-width: 1.5px; " +
               "-fx-padding: 6 14 6 14; " +
               "-fx-background-radius: 6; " +
               "-fx-border-radius: 6; " +
               "-fx-cursor: hand;";
    }

    private void applyGlobalStyles(Scene scene) {
        String css = 
            ".scroll-bar:vertical { -fx-background-color: transparent; } " +
            ".scroll-bar:vertical .thumb { -fx-background-color: rgba(0, 240, 255, 0.3); -fx-background-radius: 4; } " +
            ".text-area, .text-area .content, .text-area .viewport, .text-area:focused .content { " +
            "   -fx-background-color: transparent !important; " +
            "   -fx-control-inner-background: transparent !important; " +
            "   -fx-border-width: 0 !important; " +
            "   -fx-border-color: transparent !important; " +
            "} " +
            ".text-area:focused, .text-field:focused { " +
            "   -fx-focus-color: transparent !important; " +
            "   -fx-faint-focus-color: transparent !important; " +
            "} " +
            ".date-picker .text-field { -fx-background-color: rgba(4, 14, 20, 0.85); -fx-text-fill: #00f0ff; -fx-font-weight: bold; -fx-padding: 2 6 2 6; -fx-font-size: 11px; } " +
            ".date-picker .button { -fx-background-color: rgba(0, 240, 255, 0.2); -fx-padding: 2 6 2 6; } " +
            
            "/* WINDOWS TASKBAR CALENDAR POPUP */ " +
            ".date-picker-popup { " +
            "   -fx-background-color: #121212 !important; " +
            "   -fx-border-color: #333333 !important; " +
            "   -fx-border-width: 1px; " +
            "   -fx-border-radius: 8px; " +
            "   -fx-background-radius: 8px; " +
            "   -fx-padding: 10px; " +
            "} " +
            ".date-picker-popup .month-year-pane { -fx-background-color: transparent; -fx-padding: 4px 0 8px 0; } " +
            ".date-picker-popup .month-year-pane .label, .date-picker-popup .spinner .label { -fx-text-fill: #ffffff !important; -fx-font-size: 13px; -fx-font-weight: bold; } " +
            ".date-picker-popup .spinner .button { -fx-background-color: transparent; -fx-cursor: hand; } " +
            ".date-picker-popup .spinner .button:hover { -fx-background-color: rgba(255, 255, 255, 0.15) !important; -fx-background-radius: 4px; } " +
            
            "/* DAY NAMES HEADER: BLACK TEXT ON LIGHT GREY HEADER BACKGROUND */ " +
            ".date-picker-popup .day-name-cell { " +
            "   -fx-text-fill: #000000 !important; " +
            "   -fx-background-color: #e2e8f0 !important; " +
            "   -fx-font-size: 11px; " +
            "   -fx-font-weight: bold; " +
            "   -fx-padding: 2px 0 2px 0; " +
            "   -fx-background-radius: 3px; " +
            "} " +
            ".date-picker-popup .day-name-cell .text { -fx-fill: #000000 !important; } " +
            
            "/* CELL TEXT INHERITANCE */ " +
            ".date-picker-popup .day-cell .text { -fx-fill: inherit !important; } " +
            ".date-picker-popup .day-cell:hover { -fx-background-color: #444444 !important; -fx-text-fill: #ffffff !important; -fx-cursor: hand; } ";

        scene.getStylesheets().add("data:text/css," + css.replace(" ", "%20"));
    }

    // ==========================================
    // DATA MODEL
    // ==========================================
    public static class JournalEntry {
        private final String id;
        private String title;
        private String content;
        private final String dateTime;

        public JournalEntry(String id, String title, String content, String dateTime) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.dateTime = dateTime;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getDateTime() { return dateTime; }

        public String toJson() {
            return String.format("{\"id\":\"%s\",\"title\":\"%s\",\"content\":\"%s\",\"date\":\"%s\"}",
                escape(id), escape(title), escape(content), escape(dateTime));
        }

        public static JournalEntry fromJson(String json) {
            String id = extract(json, "id");
            String title = extract(json, "title");
            String content = extract(json, "content");
            String date = extract(json, "date");
            return new JournalEntry(id, title, content, date);
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }

        private static String unescape(String s) {
            return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        }

        private static String extract(String json, String key) {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start == -1) return "";
            start += pattern.length();
            int end = start;
            while (end < json.length()) {
                if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                end++;
            }
            return unescape(json.substring(start, end));
        }
    }
}