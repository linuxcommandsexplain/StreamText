package streamtext;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class AdvancedLargeFileViewer extends Application {
    
    private TextArea textArea;
    private Label statusLabel;
    private Label fileInfoLabel;
    private ProgressBar progressBar;
    private TextField searchField;
    private ComboBox<String> encodingCombo;
    private ComboBox<Integer> chunkSizeCombo;
    
    private AsynchronousFileChannel fileChannel;
    private long fileSize;
    private long currentPosition = 0;
    private int currentChunkSize = 1024 * 1024; // 1 MB par défaut
    private Charset currentCharset = StandardCharsets.UTF_8;
    
    private List<Long> searchResults = new ArrayList<>();
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Advanced Large File Viewer - Ultra Léger");
        
        // Zone de texte
        textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 11px;");
        
        // Menu Bar
        MenuBar menuBar = createMenuBar(primaryStage);
        
        // Barre d'outils principale
        HBox mainToolBar = createMainToolBar(primaryStage);
        
        // Barre de recherche
        HBox searchBar = createSearchBar();
        
        // Barre de navigation
        HBox navigationBar = createNavigationBar();
        
        // Options
        HBox optionsBar = createOptionsBar();
        
        // Barre de progression
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        
        // Informations
        fileInfoLabel = new Label("Aucun fichier ouvert - Mémoire optimisée");
        fileInfoLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2E7D32;");
        
        statusLabel = new Label("Prêt | Consommation mémoire minimale");
        
        VBox infoBox = new VBox(5, fileInfoLabel, statusLabel);
        infoBox.setPadding(new Insets(5, 10, 5, 10));
        infoBox.setStyle("-fx-background-color: #F5F5F5;");
        
        // Layout principal
        VBox topContainer = new VBox(menuBar, mainToolBar, searchBar, navigationBar, optionsBar, progressBar);
        
        BorderPane root = new BorderPane();
        root.setTop(topContainer);
        root.setCenter(textArea);
        root.setBottom(infoBox);
        
        Scene scene = new Scene(root, 1200, 800);
        primaryStage.setScene(scene);
        primaryStage.show();
        
        primaryStage.setOnCloseRequest(e -> closeFileChannel());
        
        updateMemoryInfo();
    }
    
    private MenuBar createMenuBar(Stage stage) {
        MenuBar menuBar = new MenuBar();
        
        // Menu Fichier
        Menu fileMenu = new Menu("Fichier");
        MenuItem openItem = new MenuItem("Ouvrir...");
        openItem.setOnAction(e -> openFile(stage));
        MenuItem closeItem = new MenuItem("Fermer");
        closeItem.setOnAction(e -> closeCurrentFile());
        MenuItem exitItem = new MenuItem("Quitter");
        exitItem.setOnAction(e -> Platform.exit());
        fileMenu.getItems().addAll(openItem, closeItem, new SeparatorMenuItem(), exitItem);
        
        // Menu Affichage
        Menu viewMenu = new Menu("Affichage");
        CheckMenuItem wrapTextItem = new CheckMenuItem("Retour à la ligne");
        wrapTextItem.setOnAction(e -> textArea.setWrapText(wrapTextItem.isSelected()));
        MenuItem refreshItem = new MenuItem("Rafraîchir");
        refreshItem.setOnAction(e -> loadChunkAtPosition(currentPosition));
        viewMenu.getItems().addAll(wrapTextItem, refreshItem);
        
        // Menu Aide
        Menu helpMenu = new Menu("Aide");
        MenuItem aboutItem = new MenuItem("À propos");
        aboutItem.setOnAction(e -> showAboutDialog());
        MenuItem memoryItem = new MenuItem("Informations mémoire");
        memoryItem.setOnAction(e -> showMemoryDialog());
        helpMenu.getItems().addAll(aboutItem, memoryItem);
        
        menuBar.getMenus().addAll(fileMenu, viewMenu, helpMenu);
        return menuBar;
    }
    
    private HBox createMainToolBar(Stage stage) {
        Button openButton = new Button("📂 Ouvrir");
        openButton.setOnAction(e -> openFile(stage));
        
        Button refreshButton = new Button("🔄 Rafraîchir");
        refreshButton.setOnAction(e -> loadChunkAtPosition(currentPosition));
        
        Button startButton = new Button("⏮ Début");
        startButton.setOnAction(e -> goToStart());
        
        Button endButton = new Button("⏭ Fin");
        endButton.setOnAction(e -> goToEnd());
        
        HBox toolBar = new HBox(10, openButton, refreshButton, new Separator(), 
                                startButton, endButton);
        toolBar.setPadding(new Insets(10));
        toolBar.setAlignment(Pos.CENTER_LEFT);
        toolBar.setStyle("-fx-background-color: #ECEFF1;");
        
        return toolBar;
    }
    
    private HBox createSearchBar() {
        Label searchLabel = new Label("Rechercher:");
        searchField = new TextField();
        searchField.setPrefWidth(300);
        searchField.setPromptText("Entrez un texte à rechercher...");
        
        Button searchButton = new Button("🔍 Rechercher");
        searchButton.setOnAction(e -> performSearch());
        
        Button prevResultButton = new Button("◀ Précédent");
        prevResultButton.setOnAction(e -> goToPreviousSearchResult());
        
        Button nextResultButton = new Button("Suivant ▶");
        nextResultButton.setOnAction(e -> goToNextSearchResult());
        
        Button clearSearchButton = new Button("✖ Effacer");
        clearSearchButton.setOnAction(e -> clearSearch());
        
        HBox searchBar = new HBox(10, searchLabel, searchField, searchButton, 
                                  prevResultButton, nextResultButton, clearSearchButton);
        searchBar.setPadding(new Insets(5, 10, 5, 10));
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setStyle("-fx-background-color: #FFF9C4;");
        
        return searchBar;
    }
    
    private HBox createNavigationBar() {
        Button prevButton = new Button("◀◀ Chunk précédent");
        prevButton.setOnAction(e -> loadPreviousChunk());
        
        TextField positionField = new TextField("0");
        positionField.setPrefWidth(150);
        positionField.setPromptText("Position (bytes)");
        
        Button goToButton = new Button("Aller à");
        goToButton.setOnAction(e -> {
            try {
                long pos = Long.parseLong(positionField.getText());
                goToPosition(pos);
            } catch (NumberFormatException ex) {
                statusLabel.setText("Position invalide");
            }
        });
        
        Button nextButton = new Button("Chunk suivant ▶▶");
        nextButton.setOnAction(e -> loadNextChunk());
        
        HBox navBar = new HBox(10, prevButton, positionField, goToButton, nextButton);
        navBar.setPadding(new Insets(5, 10, 5, 10));
        navBar.setAlignment(Pos.CENTER);
        navBar.setStyle("-fx-background-color: #E3F2FD;");
        
        return navBar;
    }
    
    private HBox createOptionsBar() {
        Label encodingLabel = new Label("Encodage:");
        encodingCombo = new ComboBox<>();
        encodingCombo.getItems().addAll("UTF-8", "ISO-8859-1", "Windows-1252", "UTF-16");
        encodingCombo.setValue("UTF-8");
        encodingCombo.setOnAction(e -> changeEncoding());
        
        Label chunkLabel = new Label("Taille chunk:");
        chunkSizeCombo = new ComboBox<>();
        chunkSizeCombo.getItems().addAll(512, 1024, 2048, 4096, 8192);
        chunkSizeCombo.setValue(1024);
        chunkSizeCombo.setOnAction(e -> changeChunkSize());
        
        Label kbLabel = new Label("KB");
        
        HBox optionsBar = new HBox(10, encodingLabel, encodingCombo, 
                                   new Separator(), chunkLabel, chunkSizeCombo, kbLabel);
        optionsBar.setPadding(new Insets(5, 10, 5, 10));
        optionsBar.setAlignment(Pos.CENTER_LEFT);
        optionsBar.setStyle("-fx-background-color: #F3E5F5;");
        
        return optionsBar;
    }
    
    private void openFile(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choisir un fichier texte");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Fichiers texte", "*.txt", "*.log", "*.csv", "*.json", "*.xml"),
            new FileChooser.ExtensionFilter("Tous les fichiers", "*.*")
        );
        
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            openFileAsync(file);
        }
    }
    
    private void openFileAsync(File file) {
        closeFileChannel();
        
        try {
            fileChannel = AsynchronousFileChannel.open(
                file.toPath(),
                StandardOpenOption.READ
            );
            
            fileSize = fileChannel.size();
            currentPosition = 0;
            
            String sizeInfo = formatFileSize(fileSize);
            fileInfoLabel.setText("📄 " + file.getName() + " (" + sizeInfo + ") - Mode économie mémoire");
            
            loadChunkAtPosition(0);
            
        } catch (IOException e) {
            showError("Erreur lors de l'ouverture du fichier: " + e.getMessage());
        }
    }
    
    private void loadChunkAtPosition(long position) {
        if (fileChannel == null) return;
        
        if (position < 0) position = 0;
        if (position >= fileSize) position = Math.max(0, fileSize - currentChunkSize);
        
        final long finalPosition = position;
        currentPosition = finalPosition;
        
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText("⏳ Chargement du chunk à la position " + formatFileSize(finalPosition) + "...");
        textArea.clear();
        
        int readSize = (int) Math.min(currentChunkSize, fileSize - finalPosition);
        ByteBuffer buffer = ByteBuffer.allocate(readSize);
        
        fileChannel.read(buffer, finalPosition, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer bytesRead, ByteBuffer attachment) {
                attachment.flip();
                
                byte[] data = new byte[attachment.remaining()];
                attachment.get(data);
                
                String content = new String(data, currentCharset);
                
                Platform.runLater(() -> {
                    textArea.setText(content);
                    textArea.positionCaret(0);
                    
                    progressBar.setVisible(false);
                    
                    double progress = (double) finalPosition / fileSize * 100;
                    statusLabel.setText(String.format("✓ Position: %s / %s (%.1f%%) | %d bytes lus",
                        formatFileSize(finalPosition), formatFileSize(fileSize), progress, bytesRead));
                    
                    updateMemoryInfo();
                });
            }
            
            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                Platform.runLater(() -> {
                    showError("Erreur de lecture: " + exc.getMessage());
                    progressBar.setVisible(false);
                });
            }
        });
    }
    
    private void loadNextChunk() {
        long nextPosition = currentPosition + currentChunkSize;
        if (nextPosition < fileSize) {
            loadChunkAtPosition(nextPosition);
        } else {
            statusLabel.setText("⚠ Fin du fichier atteinte");
        }
    }
    
    private void loadPreviousChunk() {
        long prevPosition = currentPosition - currentChunkSize;
        loadChunkAtPosition(prevPosition);
    }
    
    private void goToStart() {
        loadChunkAtPosition(0);
    }
    
    private void goToEnd() {
        long lastChunkPosition = Math.max(0, fileSize - currentChunkSize);
        loadChunkAtPosition(lastChunkPosition);
    }
    
    private void goToPosition(long position) {
        loadChunkAtPosition(position);
    }
    
    private void performSearch() {
        String searchText = searchField.getText();
        if (searchText.isEmpty()) {
            statusLabel.setText("⚠ Veuillez entrer un texte à rechercher");
            return;
        }
        
        // Recherche dans le chunk actuel
        String content = textArea.getText();
        int index = content.indexOf(searchText);
        
        if (index != -1) {
            textArea.selectRange(index, index + searchText.length());
            textArea.requestFocus();
            statusLabel.setText("✓ Texte trouvé à la position " + index);
        } else {
            statusLabel.setText("✗ Texte non trouvé dans le chunk actuel");
        }
    }
    
    private void goToNextSearchResult() {
        String searchText = searchField.getText();
        if (searchText.isEmpty()) return;
        
        String content = textArea.getText();
        int currentPos = textArea.getSelection().getEnd();
        int index = content.indexOf(searchText, currentPos);
        
        if (index != -1) {
            textArea.selectRange(index, index + searchText.length());
            textArea.requestFocus();
        } else {
            statusLabel.setText("✗ Aucune occurrence suivante dans ce chunk");
        }
    }
    
    private void goToPreviousSearchResult() {
        String searchText = searchField.getText();
        if (searchText.isEmpty()) return;
        
        String content = textArea.getText();
        int currentPos = textArea.getSelection().getStart();
        int index = content.lastIndexOf(searchText, Math.max(0, currentPos - 1));
        
        if (index != -1) {
            textArea.selectRange(index, index + searchText.length());
            textArea.requestFocus();
        } else {
            statusLabel.setText("✗ Aucune occurrence précédente dans ce chunk");
        }
    }
    
    private void clearSearch() {
        searchField.clear();
        searchResults.clear();
        textArea.deselect();
    }
    
    private void changeEncoding() {
        String encoding = encodingCombo.getValue();
        switch (encoding) {
            case "UTF-8":
                currentCharset = StandardCharsets.UTF_8;
                break;
            case "ISO-8859-1":
                currentCharset = StandardCharsets.ISO_8859_1;
                break;
            case "Windows-1252":
                currentCharset = Charset.forName("Windows-1252");
                break;
            case "UTF-16":
                currentCharset = StandardCharsets.UTF_16;
                break;
        }
        
        // Recharger le chunk actuel avec le nouvel encodage
        if (fileChannel != null) {
            loadChunkAtPosition(currentPosition);
        }
    }
    
    private void changeChunkSize() {
        currentChunkSize = chunkSizeCombo.getValue() * 1024; // Conversion en bytes
        statusLabel.setText("✓ Taille du chunk changée à " + formatFileSize(currentChunkSize));
    }
    
    private void closeCurrentFile() {
        closeFileChannel();
        textArea.clear();
        fileInfoLabel.setText("Aucun fichier ouvert");
        statusLabel.setText("Prêt");
        currentPosition = 0;
        fileSize = 0;
    }
    
    private void closeFileChannel() {
        if (fileChannel != null && fileChannel.isOpen()) {
            try {
                fileChannel.close();
            } catch (IOException e) {
                // Ignorer
            }
        }
    }
    
    private void updateMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        
        String memInfo = String.format(" | 💾 Mémoire: %d MB / %d MB", usedMemory, maxMemory);
        statusLabel.setText(statusLabel.getText() + memInfo);
    }
    
    private void showMemoryDialog() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        
        String message = String.format(
            "Mémoire utilisée: %d MB\n" +
            "Mémoire libre: %d MB\n" +
            "Mémoire totale: %d MB\n" +
            "Mémoire maximale: %d MB\n\n" +
            "Cette application utilise un chargement par chunks pour\n" +
            "minimiser l'utilisation de la mémoire, même pour des fichiers\n" +
            "de plusieurs dizaines de gigaoctets.",
            usedMemory, freeMemory, totalMemory, maxMemory
        );
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Informations mémoire");
        alert.setHeaderText("Consommation mémoire de l'application");
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("À propos");
        alert.setHeaderText("Advanced Large File Viewer");
        alert.setContentText(
            "Version 2.0\n\n" +
            "Visualiseur de fichiers texte ultra-léger utilisant\n" +
            "AsynchronousFileChannel pour une performance optimale.\n\n" +
            "Caractéristiques:\n" +
            "• Lecture asynchrone non-bloquante\n" +
            "• Consommation mémoire minimale\n" +
            "• Support de fichiers de plusieurs Go\n" +
            "• Recherche dans le chunk actuel\n" +
            "• Multiples encodages\n" +
            "• Navigation par chunks personnalisables"
        );
        alert.showAndWait();
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Erreur");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
