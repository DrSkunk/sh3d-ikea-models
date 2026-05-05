package com.drskunk.sh3dikea;

import com.eteks.sweethome3d.tools.OperatingSystem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/** Persists country / language between sessions. */
public final class IkeaSettings {

    private static final String FILE_NAME = "IkeaBrowser/settings.properties";
    private static final String DEFAULT_COUNTRY = "us";
    private static final String DEFAULT_LANGUAGE = "en";

    private final File file;
    private String country;
    private String language;

    public IkeaSettings() {
        File appFolder;
        try {
            appFolder = OperatingSystem.getDefaultApplicationFolder();
        } catch (IOException e) {
            appFolder = new File(System.getProperty("user.home"));
        }
        this.file = new File(appFolder, FILE_NAME);
        load();
    }

    public String getCountry() { return country; }
    public String getLanguage() { return language; }

    public void update(String country, String language) {
        this.country = (country == null || country.trim().isEmpty()) ? DEFAULT_COUNTRY : country.trim().toLowerCase();
        this.language = (language == null || language.trim().isEmpty()) ? DEFAULT_LANGUAGE : language.trim().toLowerCase();
        save();
    }

    private void load() {
        country = DEFAULT_COUNTRY;
        language = DEFAULT_LANGUAGE;
        if (!file.exists()) return;
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            p.load(fis);
            country = p.getProperty("country", DEFAULT_COUNTRY).toLowerCase();
            language = p.getProperty("language", DEFAULT_LANGUAGE).toLowerCase();
        } catch (IOException ignored) {
            // fall back to defaults
        }
    }

    private void save() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        Properties p = new Properties();
        p.setProperty("country", country);
        p.setProperty("language", language);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            p.store(fos, "Sweet Home 3D IKEA Browser settings");
        } catch (IOException ignored) {
            // best effort
        }
    }
}
