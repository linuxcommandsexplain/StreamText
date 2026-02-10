package streamtext;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

public class AutoUpdater {

    private static final String GITHUB_REPO = "linuxcommandsexplain/StreamText";
    private static final String CURRENT_VERSION = "1.1.4";
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

    public void checkForUpdates() {
        new Thread(() -> {
            try {
                URL url = new URI(GITHUB_API_URL).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) response.append(line);
                    in.close();

                    JSONObject json = new JSONObject(response.toString());
                    String latestVersion = json.getString("tag_name").replace("v", "");

                    if (isNewerVersion(latestVersion, CURRENT_VERSION)) {
                        JSONArray assets = json.getJSONArray("assets");
                        String downloadUrl = null;

                        // Chercher le .deb
                        for (int i = 0; i < assets.length(); i++) {
                            String assetUrl = assets.getJSONObject(i).getString("browser_download_url");
                            if (assetUrl.endsWith(".deb")) {
                                downloadUrl = assetUrl;
                                break;
                            }
                        }

                        if (downloadUrl == null && assets.length() > 0) {
                            downloadUrl = assets.getJSONObject(0).getString("browser_download_url");
                        }

                        final String finalUrl = downloadUrl;
                        Platform.runLater(() -> callback.onUpdateAvailable(latestVersion, finalUrl));
                    } else {
                        Platform.runLater(() -> callback.onNoUpdate());
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                Platform.runLater(() -> callback.onError("Erreur: " + e.getMessage()));
            }
        }).start();
    }

    public void downloadUpdate(String downloadUrl) {
        new Thread(() -> {
            try {
                URL url = new URI(downloadUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                int fileSize = conn.getContentLength();

                Path tempFile = Files.createTempFile("streamtext-update-", ".deb");

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
                Platform.runLater(() -> callback.onDownloadComplete(tempFile));
            } catch (Exception e) {
                Platform.runLater(() -> callback.onError("Erreur de téléchargement: " + e.getMessage()));
            }
        }).start();
    }

    public void installUpdate(Path updateFile) {
        new Thread(() -> {
            try {
                // Lance l'installation en arrière-plan SANS fermer l'app immédiatement
                ProcessBuilder pb = new ProcessBuilder("pkexec", "apt-get", "install", "-y", updateFile.toAbsolutePath().toString());
                Process process = pb.start();

                // Attend que l'installation se termine
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    // Installation réussie, maintenant on peut fermer
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Installation terminée");
                        alert.setHeaderText("Mise à jour installée avec succès");
                        alert.setContentText("L'application va maintenant redémarrer.");
                        alert.showAndWait();

                        Platform.exit();
                        System.exit(0);
                    });
                } else {
                    Platform.runLater(() -> callback.onError("Erreur lors de l'installation (code: " + exitCode + ")"));
                }
            } catch (Exception e) {
                Platform.runLater(() -> callback.onError("Erreur d'installation: " + e.getMessage()));
            }
        }).start();
    }

    private boolean isNewerVersion(String latest, String current) {
        String[] latestParts = latest.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i].replaceAll("[^0-9]", "")) : 0;
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i].replaceAll("[^0-9]", "")) : 0;
            if (latestPart > currentPart) return true;
            if (latestPart < currentPart) return false;
        }
        return false;
    }

    public static void showUpdateDialog(String version, String downloadUrl, AutoUpdater updater) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Mise à jour disponible");
        alert.setHeaderText("Version " + version + " disponible");
        alert.setContentText("Voulez-vous installer la mise à jour maintenant ?");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            updater.downloadUpdate(downloadUrl);
        }
    }

    public static String getCurrentVersion() {
        return CURRENT_VERSION;
    }
}
