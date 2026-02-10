package streamtext;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

public class AutoUpdater {

    private static final String GITHUB_REPO = "linuxcommandsexplain/StreamText"; // À MODIFIER
    private static final String CURRENT_VERSION = "1.0.0"; // À MODIFIER
    private static final String GITHUB_API_URL = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    
    private UpdateCallback callback;
    
    public interface UpdateCallback {
        void onUpdateAvailable(String version, String downloadUrl);
        void onNoUpdate();
        void onError(String error);
        void onDownloadProgress(int progress);
        void onDownloadComplete(Path filePath);
    }
    
    public AutoUpdater(UpdateCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Vérifie s'il y a une mise à jour disponible
     */
    public void checkForUpdates() {
        new Thread(() -> {
            try {
                URL url = new URI(GITHUB_API_URL).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    in.close();
                    
                    JSONObject json = new JSONObject(response.toString());
                    String latestVersion = json.getString("tag_name").replace("v", "");
                    
                    if (isNewerVersion(latestVersion, CURRENT_VERSION)) {
                        String downloadUrl = json.getJSONArray("assets")
                            .getJSONObject(0)
                            .getString("browser_download_url");
                        
                        Platform.runLater(() -> callback.onUpdateAvailable(latestVersion, downloadUrl));
                    } else {
                        Platform.runLater(() -> callback.onNoUpdate());
                    }
                } else {
                    Platform.runLater(() -> callback.onError("Erreur HTTP: " + responseCode));
                }
                
                conn.disconnect();
                
            } catch (Exception e) {
                Platform.runLater(() -> callback.onError("Erreur: " + e.getMessage()));
            }
        }).start();
    }
    
    /**
     * Télécharge la mise à jour
     */
    public void downloadUpdate(String downloadUrl) {
        new Thread(() -> {
            try {
                URL url = new URI(downloadUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                int fileSize = conn.getContentLength();
                
                Path tempFile = Files.createTempFile("streamtext-update-", ".jar");
                
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tempFile.toFile())) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytesRead = 0;
                    
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                        
                        int progress = (int) ((totalBytesRead * 100) / fileSize);
                        Platform.runLater(() -> callback.onDownloadProgress(progress));
                    }
                }
                
                conn.disconnect();
                Platform.runLater(() -> callback.onDownloadComplete(tempFile));
                
            } catch (Exception e) {
                Platform.runLater(() -> callback.onError("Erreur de téléchargement: " + e.getMessage()));
            }
        }).start();
    }
    
    /**
     * Compare deux versions
     */
    private boolean isNewerVersion(String latest, String current) {
        String[] latestParts = latest.split("\\.");
        String[] currentParts = current.split("\\.");
        
        int length = Math.max(latestParts.length, currentParts.length);
        
        for (int i = 0; i < length; i++) {
            int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            
            if (latestPart > currentPart) {
                return true;
            } else if (latestPart < currentPart) {
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * Installe la mise à jour et redémarre l'application
     */
    public void installUpdate(Path updateFile) {
        try {
            // Obtenir le chemin du JAR actuel
            String jarPath = AutoUpdater.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            
            Path currentJar = Paths.get(jarPath);
            
            // Créer un script de mise à jour
            Path updateScript = Files.createTempFile("update-", ".sh");
            
            String script = String.format(
                "#!/bin/bash\n" +
                "sleep 2\n" +
                "mv '%s' '%s.old'\n" +
                "mv '%s' '%s'\n" +
                "java -jar '%s' &\n" +
                "rm '%s'\n",
                currentJar, currentJar, updateFile, currentJar, currentJar, updateScript
            );
            
            Files.write(updateScript, script.getBytes());
            updateScript.toFile().setExecutable(true);
            
            // Lancer le script et quitter
            Runtime.getRuntime().exec(new String[]{"sh", updateScript.toString()});
            Platform.exit();
            System.exit(0);
            
        } catch (Exception e) {
            callback.onError("Erreur d'installation: " + e.getMessage());
        }
    }
    
    /**
     * Affiche une boîte de dialogue pour demander la mise à jour
     */
    public static void showUpdateDialog(String version, String downloadUrl, AutoUpdater updater) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Mise à jour disponible");
        alert.setHeaderText("Version " + version + " disponible");
        alert.setContentText(
            "Une nouvelle version de StreamText est disponible.\n\n" +
            "Version actuelle: " + CURRENT_VERSION + "\n" +
            "Nouvelle version: " + version + "\n\n" +
            "Voulez-vous télécharger et installer la mise à jour maintenant ?"
        );
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Afficher une barre de progression
            Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
            progressAlert.setTitle("Téléchargement");
            progressAlert.setHeaderText("Téléchargement de la mise à jour...");
            progressAlert.setContentText("0%");
            progressAlert.show();
            
            updater.downloadUpdate(downloadUrl);
        }
    }
    
    public static String getCurrentVersion() {
        return CURRENT_VERSION;
    }
    
}
