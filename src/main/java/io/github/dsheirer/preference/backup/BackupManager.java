/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.preference.backup;

import io.github.dsheirer.preference.decoder.JmbeLibraryPreference;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.preference.playlist.PlaylistPreference;
import io.github.dsheirer.properties.SystemProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup and migration of the user's SDRTrunk setup: preferences, playlists, tuner settings, icons, map/color
 * settings and the JMBE library files, all in one zip file.
 *
 * Safety rules:
 * - Creating a backup only READS the source files and preferences.  Nothing on the source is written or changed.
 * - Every backup is written to a temporary file, re-opened and checked entry by entry (SHA-256) against what was
 *   read, and only then given its final name.  A failed or partial backup never keeps the final name.
 * - A restore is checked completely before anything is changed.  A backup of the current setup is made first.
 * - The restore itself is staged and applied at the next application start, before the preferences, playlist,
 *   tuners or icons are loaded, so the running application can never write its old copies over restored files.
 * - Restored files are written to a temporary file next to the destination and then moved into place.
 *
 * Machine-specific settings (folder locations, window sizes/positions, audio output device, CPU calibration,
 * recent files) are never included.  Playlist and JMBE library locations are rewritten for the new computer.
 */
public class BackupManager
{
    private static final Logger mLog = LoggerFactory.getLogger(BackupManager.class);

    public static final int FORMAT_VERSION = 1;
    public static final String BACKUPS_FOLDER = "backups";
    private static final String PENDING_RESTORE = "pending-restore.zip";
    private static final String PENDING_RESTORE_INFO = "pending-restore.properties";

    private static final String ENTRY_MANIFEST = "manifest.properties";
    private static final String ENTRY_PREFERENCES = "preferences.properties";
    private static final String FILES = "files/";

    private static final String ROLE_PREFERENCES = "preferences";
    private static final String ROLE_PLAYLIST = "playlist";
    private static final String ROLE_TUNERS = "tuners";
    private static final String ROLE_ICONS = "icons";
    private static final String ROLE_SETTINGS = "settings";
    private static final String ROLE_PROPERTIES = "properties";
    private static final String ROLE_JMBE = "jmbe";
    private static final String ROLE_ICON_IMAGE = "icon-image";
    private static final String ICON_IMAGES_FOLDER = "icons";

    private static final String MANIFEST_FORMAT = "format";
    private static final String MANIFEST_APPLICATION = "application";
    private static final String MANIFEST_CREATED = "created";
    private static final String MANIFEST_OS = "os";
    private static final String MANIFEST_ENTRY_COUNT = "entry.count";
    private static final String MANIFEST_ACTIVE_PLAYLIST = "playlist.active";
    private static final String MANIFEST_JMBE_IMBE = "jmbe.imbe";
    private static final String MANIFEST_JMBE_AMBE = "jmbe.ambe";

    private static final String PREFERENCES_ROOT = "io/github/dsheirer";
    private static final String NODE_PREFIX = "/io/github/dsheirer/";
    private static final String KEY_SEPARATOR = "|";

    private static final String TUNER_CONFIGURATION_FILE = "tuner_configuration.json";
    private static final String ICONS_FILE = "icons.xml";
    private static final String SETTINGS_FOLDER = "settings";
    private static final String PROPERTIES_FILE = "SDRTrunk.properties";
    private static final String SETTINGS_CURRENT_FILENAME = "settings.currentFilename";
    private static final String SETTINGS_DEFAULT_FILENAME = "settings.xml";
    private static final String ROOT_DIRECTORY_PROPERTY = "root.directory";

    /** Values that are absolute file system paths are machine-specific and are never copied */
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
        "^([A-Za-z]:[\\\\/]|\\\\\\\\|/(Users|home|root|Volumes|mnt|media|opt|var|tmp|private|Library|Applications)/).*");

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    /** Set at startup when a restore was completed, for the "Restore Complete" message */
    private static Path sCompletedRestoreUndoPath;
    /** Set at startup when a staged restore could not be used */
    private static boolean sRestoreFailed;

    private final DirectoryPreference mDirectoryPreference;
    private final PlaylistPreference mPlaylistPreference;
    private final JmbeLibraryPreference mJmbeLibraryPreference;

    /**
     * Constructs an instance.
     */
    public BackupManager(DirectoryPreference directoryPreference, PlaylistPreference playlistPreference,
                         JmbeLibraryPreference jmbeLibraryPreference)
    {
        mDirectoryPreference = directoryPreference;
        mPlaylistPreference = playlistPreference;
        mJmbeLibraryPreference = jmbeLibraryPreference;
    }

    /**
     * Default file name for a new backup.
     */
    public static String getDefaultBackupFileName()
    {
        return "sdrtrunk-backup-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".zip";
    }

    /**
     * Name of the running application (version and build timestamp) used to compare backup versions.
     */
    public static String getApplicationVersion()
    {
        try
        {
            return SystemProperties.getInstance().getApplicationName();
        }
        catch(Exception e)
        {
            return "unknown";
        }
    }

    /**
     * Folder that holds the automatic before-restore backups and the staged restore.
     */
    public Path getBackupsFolder() throws IOException
    {
        Path folder = mDirectoryPreference.getDirectoryApplicationRoot().resolve(BACKUPS_FOLDER);
        Files.createDirectories(folder);
        return folder;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Creates a backup file at the target path.  Only reads from the current setup.
     * @param target path for the backup file
     * @throws BackupException if the backup could not be created and verified.  The target is not created.
     */
    public void createBackup(Path target) throws BackupException
    {
        Map<String,byte[]> entries = new LinkedHashMap<>();
        Map<String,String> roles = new LinkedHashMap<>();
        Properties manifest = new Properties();

        try
        {
            //Preferences
            entries.put(ENTRY_PREFERENCES, exportPreferences());
            roles.put(ENTRY_PREFERENCES, ROLE_PREFERENCES);

            //Playlists
            Path activePlaylist = mPlaylistPreference.getPlaylist();
            List<String> usedNames = new ArrayList<>();

            for(Path playlist: mPlaylistPreference.getPlaylistList())
            {
                if(playlist != null && Files.isRegularFile(playlist))
                {
                    byte[] data = readPlaylist(playlist);
                    String name = uniqueName(playlist.getFileName().toString(), usedNames);
                    String entry = FILES + "playlist/" + name;
                    entries.put(entry, data);
                    roles.put(entry, ROLE_PLAYLIST);

                    if(activePlaylist != null && playlist.toAbsolutePath().normalize()
                        .equals(activePlaylist.toAbsolutePath().normalize()))
                    {
                        manifest.setProperty(MANIFEST_ACTIVE_PLAYLIST, name);
                    }
                }
            }

            //Tuner settings
            Path tuners = mDirectoryPreference.getDirectoryConfiguration().resolve(TUNER_CONFIGURATION_FILE);

            if(Files.isRegularFile(tuners))
            {
                byte[] data = Files.readAllBytes(tuners);
                checkJson(data, TUNER_CONFIGURATION_FILE);
                String entry = FILES + "configuration/" + TUNER_CONFIGURATION_FILE;
                entries.put(entry, data);
                roles.put(entry, ROLE_TUNERS);
            }

            //Icons and map/color settings
            Properties systemProperties = new Properties();
            SystemProperties sp = SystemProperties.getInstance();
            systemProperties.setProperty(ROOT_DIRECTORY_PROPERTY, sp.get(ROOT_DIRECTORY_PROPERTY, "SDRTrunk"));
            String current = sp.get(SETTINGS_CURRENT_FILENAME, (String)null);

            if(current != null)
            {
                systemProperties.setProperty(SETTINGS_CURRENT_FILENAME, current);
            }

            Path settingsFolder = getSettingsFolder(systemProperties);
            Path icons = settingsFolder.resolve(ICONS_FILE);

            if(Files.isRegularFile(icons))
            {
                byte[] data = Files.readAllBytes(icons);
                checkXml(data, ICONS_FILE);
                String entry = FILES + "settings/" + ICONS_FILE;
                entries.put(entry, data);
                roles.put(entry, ROLE_ICONS);
                addIconImages(data, entries, roles, manifest);
            }

            String settingsName = getSettingsFileName(systemProperties);
            Path settings = settingsFolder.resolve(settingsName);

            if(Files.isRegularFile(settings))
            {
                byte[] data = Files.readAllBytes(settings);
                checkXml(data, settingsName);
                String entry = FILES + "settings/" + SETTINGS_DEFAULT_FILENAME;
                entries.put(entry, data);
                roles.put(entry, ROLE_SETTINGS);
            }

            //Application properties (spectral display, audio panel colors)
            Path properties = mDirectoryPreference.getDirectoryApplicationRoot().resolve(PROPERTIES_FILE);

            if(Files.isRegularFile(properties))
            {
                byte[] data = Files.readAllBytes(properties);
                new Properties().load(new ByteArrayInputStream(data));
                String entry = FILES + PROPERTIES_FILE;
                entries.put(entry, data);
                roles.put(entry, ROLE_PROPERTIES);
            }

            //JMBE library files
            Path imbe = mJmbeLibraryPreference.getPathJmbeLibrary();
            Path ambe = mJmbeLibraryPreference.getPathJmbeAmbeLibrary();
            List<String> jmbeNames = new ArrayList<>();

            if(imbe != null && Files.isRegularFile(imbe))
            {
                String name = addJmbe(imbe, entries, roles, jmbeNames);
                manifest.setProperty(MANIFEST_JMBE_IMBE, name);
            }

            if(ambe != null && Files.isRegularFile(ambe))
            {
                String name = (imbe != null && Files.isRegularFile(imbe) && Files.isSameFile(imbe, ambe)) ?
                    manifest.getProperty(MANIFEST_JMBE_IMBE) : addJmbe(ambe, entries, roles, jmbeNames);
                manifest.setProperty(MANIFEST_JMBE_AMBE, name);
            }
        }
        catch(BackupException be)
        {
            throw be;
        }
        catch(Exception e)
        {
            throw new BackupException("Error reading the current setup for the backup", e);
        }

        //Manifest
        manifest.setProperty(MANIFEST_FORMAT, String.valueOf(FORMAT_VERSION));
        manifest.setProperty(MANIFEST_APPLICATION, getApplicationVersion());
        manifest.setProperty(MANIFEST_CREATED, LocalDateTime.now().toString());
        manifest.setProperty(MANIFEST_OS, System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""));
        manifest.setProperty(MANIFEST_ENTRY_COUNT, String.valueOf(entries.size()));

        int index = 0;

        for(Map.Entry<String,byte[]> entry: entries.entrySet())
        {
            manifest.setProperty("entry." + index + ".name", entry.getKey());
            manifest.setProperty("entry." + index + ".role", roles.get(entry.getKey()));
            manifest.setProperty("entry." + index + ".size", String.valueOf(entry.getValue().length));
            manifest.setProperty("entry." + index + ".sha256", sha256(entry.getValue()));
            index++;
        }

        writeVerified(target, manifest, entries);
        mLog.info("Backup created [" + target + "] with " + entries.size() + " items");
    }

    /**
     * Adds the pictures used by custom icons (icons that point at a picture file on this computer) to the backup.
     * Built-in icons are part of the application and are skipped.  A custom icon whose picture is missing is left
     * as it is - it doesn't stop the backup.
     * @param iconsXml contents of the icons file (only read)
     */
    private void addIconImages(byte[] iconsXml, Map<String,byte[]> entries, Map<String,String> roles,
                               Properties manifest) throws Exception
    {
        org.w3c.dom.NodeList icons = parseXml(iconsXml).getElementsByTagName("icon");
        List<String> usedNames = new ArrayList<>();
        Map<String,String> added = new LinkedHashMap<>();
        int count = 0;

        for(int x = 0; x < icons.getLength(); x++)
        {
            org.w3c.dom.Element icon = (org.w3c.dom.Element)icons.item(x);
            String path = icon.getAttribute("path");

            if(path == null || path.isBlank() || path.startsWith("images") || added.containsKey(path))
            {
                continue;
            }

            Path picture;

            try
            {
                picture = Paths.get(path);
            }
            catch(Exception e)
            {
                continue;
            }

            if(!picture.isAbsolute())
            {
                continue;
            }

            if(!Files.isRegularFile(picture))
            {
                mLog.warn("Icon picture not found - icon [" + icon.getAttribute("name") + "] left as it is [" +
                    path + "]");
                continue;
            }

            byte[] data = Files.readAllBytes(picture);

            if(data.length == 0)
            {
                continue;
            }

            String entry = FILES + "icons/" + uniqueName(picture.getFileName().toString(), usedNames);
            entries.put(entry, data);
            roles.put(entry, ROLE_ICON_IMAGE);
            manifest.setProperty("icon.image." + count + ".path", path);
            manifest.setProperty("icon.image." + count + ".entry", entry);
            added.put(path, entry);
            count++;
        }

        manifest.setProperty("icon.image.count", String.valueOf(count));
    }

    /**
     * Writes the custom icon pictures into this computer's icons folder and returns the icons file with those
     * icons pointed at the new location.  Icons without a picture in the backup are left as they are.
     * @param contents of the backup
     * @param folder for the icon pictures on this computer
     * @param iconsXml icons file from the backup
     * @return icons file to write on this computer
     */
    private static byte[] restoreIconImages(BackupContents contents, Path folder, byte[] iconsXml) throws Exception
    {
        Properties manifest = contents.getManifest();
        int count = Integer.parseInt(manifest.getProperty("icon.image.count", "0"));

        if(count <= 0)
        {
            return iconsXml;
        }

        Map<String,String> newPaths = new LinkedHashMap<>();

        for(int x = 0; x < count; x++)
        {
            String original = manifest.getProperty("icon.image." + x + ".path");
            String entry = manifest.getProperty("icon.image." + x + ".entry");
            byte[] data = entry != null ? contents.getEntries().get(entry) : null;

            if(original == null || data == null)
            {
                continue;
            }

            Files.createDirectories(folder);
            Path destination = folder.resolve(Paths.get(entry).getFileName().toString());

            //Leave an identical existing picture untouched
            if(!(Files.isRegularFile(destination) && MessageDigest.isEqual(Files.readAllBytes(destination), data)))
            {
                writeFile(destination, data);
            }

            newPaths.put(original, destination.toAbsolutePath().toString());
        }

        if(newPaths.isEmpty())
        {
            return iconsXml;
        }

        org.w3c.dom.Document document = parseXml(iconsXml);
        org.w3c.dom.NodeList icons = document.getElementsByTagName("icon");

        for(int x = 0; x < icons.getLength(); x++)
        {
            org.w3c.dom.Element icon = (org.w3c.dom.Element)icons.item(x);
            String replacement = newPaths.get(icon.getAttribute("path"));

            if(replacement != null)
            {
                icon.setAttribute("path", replacement);
            }
        }

        javax.xml.transform.TransformerFactory factory = javax.xml.transform.TransformerFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        javax.xml.transform.Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new javax.xml.transform.dom.DOMSource(document),
            new javax.xml.transform.stream.StreamResult(out));
        byte[] updated = out.toByteArray();
        checkXml(updated, ICONS_FILE);
        return updated;
    }

    private static org.w3c.dom.Document parseXml(byte[] data) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder.parse(new ByteArrayInputStream(data));
    }

    /**
     * Adds a JMBE library file to the backup entries.
     */
    private String addJmbe(Path jar, Map<String,byte[]> entries, Map<String,String> roles, List<String> usedNames)
        throws IOException, BackupException
    {
        byte[] data = Files.readAllBytes(jar);
        checkZip(data, jar.getFileName().toString());
        String name = uniqueName(jar.getFileName().toString(), usedNames);
        String entry = FILES + "jmbe/" + name;
        entries.put(entry, data);
        roles.put(entry, ROLE_JMBE);
        return name;
    }

    /**
     * Reads a playlist file, waiting briefly if the application is in the middle of saving it.
     */
    private byte[] readPlaylist(Path playlist) throws IOException, BackupException
    {
        Path lock = Paths.get(playlist.toAbsolutePath() + ".lck");

        for(int attempt = 0; attempt < 50 && Files.exists(lock); attempt++)
        {
            try
            {
                Thread.sleep(100);
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if(Files.exists(lock))
        {
            throw new BackupException("Playlist is being saved [" + playlist + "]");
        }

        byte[] data = Files.readAllBytes(playlist);
        checkXml(data, playlist.getFileName().toString());
        return data;
    }

    /**
     * Exports the application preferences (all of them, except machine-specific values) as a properties document
     * where each key is the preference node path and key, separated by a | character.
     */
    private byte[] exportPreferences() throws Exception
    {
        Properties properties = new Properties();
        java.util.prefs.Preferences root = java.util.prefs.Preferences.userRoot();

        //Check first so that reading never creates a preference node
        if(root.nodeExists(PREFERENCES_ROOT))
        {
            exportNode(root.node(PREFERENCES_ROOT), properties);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, "SDRTrunk preferences backup");
        return out.toByteArray();
    }

    private void exportNode(java.util.prefs.Preferences node, Properties properties) throws Exception
    {
        String path = node.absolutePath();

        for(String key: node.keys())
        {
            String value = node.get(key, null);

            if(value != null && !isMachineSpecific(path, key, value))
            {
                properties.setProperty(path + KEY_SEPARATOR + key, value);
            }
        }

        for(String child: node.childrenNames())
        {
            exportNode(node.node(child), properties);
        }
    }

    /**
     * Identifies preferences that belong to one computer and must not be copied to another.
     * @param nodePath absolute preference node path
     * @param key preference key
     * @param value preference value
     * @return true if the preference is machine-specific
     */
    static boolean isMachineSpecific(String nodePath, String key, String value)
    {
        //Values that are file system paths (folders, recent files, last-browsed locations)
        if(value != null && ABSOLUTE_PATH.matcher(value).matches())
        {
            return true;
        }

        //CPU calibration results
        if(nodePath.startsWith(NODE_PREFIX + "vector/calibrate"))
        {
            return true;
        }

        switch(nodePath)
        {
            case NODE_PREFIX + "gui/viewer":
                //Developer viewers - last used files and values
                return true;
            case NODE_PREFIX + "preference/backup":
                //This computer's last backup details
                return true;
            case NODE_PREFIX + "preference/directory":
                return key.startsWith("directory.") && !key.startsWith("directory.max.usage");
            case NODE_PREFIX + "preference/decoder":
                //JMBE library locations are rewritten for the new computer
                return key.startsWith("path.jmbe.library");
            case NODE_PREFIX + "preference/playlist":
                //Playlist locations are rewritten for the new computer
                return key.equals("playlist.path") || key.equals("playlist.list");
            case NODE_PREFIX + "preference/playback":
                return key.equals("audio.playback.device.name");
            case NODE_PREFIX + "preference/javafx":
                return key.startsWith("stage.") || key.equals("state.maximized");
            case NODE_PREFIX + "preference/swing":
                return key.endsWith(".x") || key.endsWith(".y") || key.endsWith(".width") ||
                    key.endsWith(".height") || key.endsWith(".maximized") || key.contains(".divider.");
            case NODE_PREFIX + "gui":
                return key.contains(".recent.file.");
            default:
                return false;
        }
    }

    /**
     * Writes the backup to a temporary file, verifies every entry, then moves it to the target name.
     */
    private void writeVerified(Path target, Properties manifest, Map<String,byte[]> entries) throws BackupException
    {
        Path temp = target.resolveSibling(target.getFileName().toString() + ".partial");

        try
        {
            if(target.getParent() != null)
            {
                Files.createDirectories(target.getParent());
            }

            try(OutputStream os = Files.newOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(os))
            {
                ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
                manifest.store(manifestBytes, "SDRTrunk backup");
                zip.putNextEntry(new ZipEntry(ENTRY_MANIFEST));
                zip.write(manifestBytes.toByteArray());
                zip.closeEntry();

                for(Map.Entry<String,byte[]> entry: entries.entrySet())
                {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }

            //Re-open the written file and check every entry against what was read
            BackupContents written = readAndVerify(temp);

            for(Map.Entry<String,byte[]> entry: entries.entrySet())
            {
                byte[] check = written.getEntries().get(entry.getKey());

                if(check == null || !MessageDigest.isEqual(check, entry.getValue()))
                {
                    throw new BackupException("Backup verification failed for [" + entry.getKey() + "]");
                }
            }

            moveIntoPlace(temp, target);
        }
        catch(BackupException be)
        {
            deleteQuietly(temp);
            throw be;
        }
        catch(Exception e)
        {
            deleteQuietly(temp);
            throw new BackupException("Error writing the backup file [" + target + "]", e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Verify
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Reads a backup file and checks it completely: the manifest, every listed entry and its SHA-256, and that the
     * preferences, XML and JSON files are readable.
     * @param backup file
     * @return verified contents
     * @throws BackupException if any part of the backup is missing or damaged
     */
    public static BackupContents readAndVerify(Path backup) throws BackupException
    {
        Map<String,byte[]> data = new LinkedHashMap<>();

        try(ZipFile zip = new ZipFile(backup.toFile()))
        {
            ZipEntry manifestEntry = zip.getEntry(ENTRY_MANIFEST);

            if(manifestEntry == null)
            {
                throw new BackupException("Backup has no manifest");
            }

            Properties manifest = new Properties();

            try(InputStream in = zip.getInputStream(manifestEntry))
            {
                manifest.load(in);
            }

            int format = Integer.parseInt(manifest.getProperty(MANIFEST_FORMAT, "-1"));

            if(format < 1 || format > FORMAT_VERSION)
            {
                throw new BackupException("Unsupported backup format [" + format + "]");
            }

            int count = Integer.parseInt(manifest.getProperty(MANIFEST_ENTRY_COUNT, "-1"));

            if(count < 1)
            {
                throw new BackupException("Backup has no entries");
            }

            Map<String,String> roles = new LinkedHashMap<>();

            for(int x = 0; x < count; x++)
            {
                String name = manifest.getProperty("entry." + x + ".name");
                String role = manifest.getProperty("entry." + x + ".role");
                String hash = manifest.getProperty("entry." + x + ".sha256");
                long size = Long.parseLong(manifest.getProperty("entry." + x + ".size", "-1"));

                if(name == null || role == null || hash == null || name.contains("..") || name.startsWith("/"))
                {
                    throw new BackupException("Backup manifest entry " + x + " is invalid");
                }

                ZipEntry zipEntry = zip.getEntry(name);

                if(zipEntry == null)
                {
                    throw new BackupException("Backup is missing [" + name + "]");
                }

                byte[] bytes;

                try(InputStream in = zip.getInputStream(zipEntry))
                {
                    bytes = in.readAllBytes();
                }

                if(bytes.length != size || !sha256(bytes).equals(hash))
                {
                    throw new BackupException("Backup entry is damaged [" + name + "]");
                }

                switch(role)
                {
                    case ROLE_PREFERENCES, ROLE_PROPERTIES -> new Properties().load(new ByteArrayInputStream(bytes));
                    case ROLE_PLAYLIST, ROLE_ICONS, ROLE_SETTINGS -> checkXml(bytes, name);
                    case ROLE_TUNERS -> checkJson(bytes, name);
                    case ROLE_JMBE -> checkZip(bytes, name);
                    case ROLE_ICON_IMAGE ->
                    {
                        if(bytes.length == 0)
                        {
                            throw new BackupException("Icon picture is empty [" + name + "]");
                        }
                    }
                    default -> throw new BackupException("Unknown backup entry type [" + role + "]");
                }

                data.put(name, bytes);
                roles.put(name, role);
            }

            if(!data.containsKey(ENTRY_PREFERENCES))
            {
                throw new BackupException("Backup has no preferences");
            }

            return new BackupContents(manifest, data, roles);
        }
        catch(BackupException be)
        {
            throw be;
        }
        catch(Exception e)
        {
            throw new BackupException("Backup file can't be read [" + backup + "]", e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Restore (stage now, apply at next start)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Name of the before-restore backup that stageRestore() will create.
     */
    public Path getBeforeRestorePath() throws IOException
    {
        return getBackupsFolder().resolve("before-restore-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".zip");
    }

    /**
     * Stages a restore: checks the backup completely, makes a backup of the current setup, then copies the backup
     * into the backups folder where it is applied at the next application start.  Nothing in the current setup is
     * changed by this method.
     * @param backup file to restore
     * @param beforeRestore path for the automatic backup of the current setup
     * @throws BackupException if the backup is damaged or the staging failed (nothing was changed)
     */
    public void stageRestore(Path backup, Path beforeRestore) throws BackupException
    {
        readAndVerify(backup);

        //Safety copy of the current setup first
        createBackup(beforeRestore);

        try
        {
            Path folder = getBackupsFolder();
            Path pending = folder.resolve(PENDING_RESTORE);
            Path temp = folder.resolve(PENDING_RESTORE + ".partial");
            Files.copy(backup, temp, StandardCopyOption.REPLACE_EXISTING);

            if(!sha256(Files.readAllBytes(temp)).equals(sha256(Files.readAllBytes(backup))))
            {
                deleteQuietly(temp);
                throw new BackupException("Copy of the backup file did not match the original");
            }

            readAndVerify(temp);

            Properties info = new Properties();
            info.setProperty("undo", beforeRestore.toAbsolutePath().toString());
            info.setProperty("source", backup.toAbsolutePath().toString());
            Path infoTemp = folder.resolve(PENDING_RESTORE_INFO + ".partial");

            try(OutputStream out = Files.newOutputStream(infoTemp))
            {
                info.store(out, "SDRTrunk pending restore");
            }

            moveIntoPlace(infoTemp, folder.resolve(PENDING_RESTORE_INFO));
            moveIntoPlace(temp, pending);
            mLog.info("Restore staged from [" + backup + "] - will be applied at next start.  Before-restore backup [" +
                beforeRestore + "]");
        }
        catch(BackupException be)
        {
            throw be;
        }
        catch(Exception e)
        {
            throw new BackupException("Error staging the restore", e);
        }
    }

    /**
     * Applies a staged restore, if there is one.  Must be called at application start before the preferences,
     * playlist, tuners or icons are loaded.
     */
    public static void applyPendingRestore()
    {
        DirectoryPreference directoryPreference;
        Path folder;

        try
        {
            directoryPreference = new DirectoryPreference(type -> {});
            folder = directoryPreference.getDirectoryApplicationRoot().resolve(BACKUPS_FOLDER);
        }
        catch(Exception e)
        {
            mLog.error("Unable to check for a pending restore", e);
            return;
        }

        Path pending = folder.resolve(PENDING_RESTORE);

        if(!Files.exists(pending))
        {
            return;
        }

        mLog.info("Applying staged restore [" + pending + "]");
        String stamp = LocalDateTime.now().format(FILE_TIMESTAMP);
        Path infoPath = folder.resolve(PENDING_RESTORE_INFO);
        Path undo = null;

        try
        {
            if(Files.exists(infoPath))
            {
                Properties info = new Properties();

                try(InputStream in = Files.newInputStream(infoPath))
                {
                    info.load(in);
                }

                String undoPath = info.getProperty("undo");

                if(undoPath != null)
                {
                    undo = Paths.get(undoPath);
                }
            }
        }
        catch(Exception e)
        {
            mLog.error("Error reading pending restore details", e);
        }

        BackupContents contents;

        try
        {
            contents = readAndVerify(pending);
        }
        catch(BackupException be)
        {
            mLog.error("Staged restore can't be used - nothing was changed", be);
            setAside(pending, folder.resolve("failed-restore-" + stamp + ".zip"));
            deleteQuietly(infoPath);
            sRestoreFailed = true;
            return;
        }

        try
        {
            apply(contents, directoryPreference);
            setAside(pending, folder.resolve("restored-" + stamp + ".zip"));
            deleteQuietly(infoPath);
            sCompletedRestoreUndoPath = undo;
            mLog.info("Restore complete");
        }
        catch(Exception e)
        {
            mLog.error("Error applying the restore - putting the previous setup back", e);
            setAside(pending, folder.resolve("failed-restore-" + stamp + ".zip"));
            deleteQuietly(infoPath);

            if(undo != null && Files.exists(undo))
            {
                try
                {
                    apply(readAndVerify(undo), directoryPreference);
                    mLog.info("Previous setup was put back from [" + undo + "]");
                }
                catch(Exception e2)
                {
                    mLog.error("Unable to put the previous setup back from [" + undo + "]", e2);
                }
            }

            sRestoreFailed = true;
        }
    }

    /**
     * Writes the backup contents into this computer's setup.
     */
    private static void apply(BackupContents contents, DirectoryPreference directoryPreference) throws Exception
    {
        Properties manifest = contents.getManifest();
        Path root = directoryPreference.getDirectoryApplicationRoot();
        Path localPropertiesPath = root.resolve(PROPERTIES_FILE);
        Properties localProperties = new Properties();

        if(Files.isRegularFile(localPropertiesPath))
        {
            try(InputStream in = Files.newInputStream(localPropertiesPath))
            {
                localProperties.load(in);
            }
        }

        //1. Preferences
        Properties preferences = new Properties();
        preferences.load(new ByteArrayInputStream(contents.getEntries().get(ENTRY_PREFERENCES)));
        java.util.prefs.Preferences prefsRoot = java.util.prefs.Preferences.userRoot();

        for(String compound: preferences.stringPropertyNames())
        {
            int separator = compound.indexOf(KEY_SEPARATOR);

            if(separator <= 0)
            {
                continue;
            }

            String nodePath = compound.substring(0, separator);
            String key = compound.substring(separator + 1);
            String value = preferences.getProperty(compound);

            if(nodePath.startsWith(NODE_PREFIX.substring(0, NODE_PREFIX.length() - 1)) &&
                !isMachineSpecific(nodePath, key, value))
            {
                prefsRoot.node(nodePath).put(key, value);
            }
        }

        prefsRoot.node(PREFERENCES_ROOT).flush();

        //2. Playlists - into this computer's playlist folder
        Path playlistFolder = directoryPreference.getDirectoryPlaylist();
        List<Path> playlists = new ArrayList<>();
        Path active = null;
        String activeName = manifest.getProperty(MANIFEST_ACTIVE_PLAYLIST);

        for(Map.Entry<String,String> entry: contents.getRoles().entrySet())
        {
            if(entry.getValue().equals(ROLE_PLAYLIST))
            {
                String name = Paths.get(entry.getKey()).getFileName().toString();
                Path destination = playlistFolder.resolve(name);
                writeFile(destination, contents.getEntries().get(entry.getKey()));

                //A leftover lock file would make the application replace this playlist with its backup copy
                deleteQuietly(Paths.get(destination.toAbsolutePath() + ".lck"));
                playlists.add(destination);

                if(name.equals(activeName))
                {
                    active = destination;
                }
            }
        }

        if(!playlists.isEmpty())
        {
            PlaylistPreference playlistPreference = new PlaylistPreference(type -> {}, directoryPreference);
            playlistPreference.setPlaylist(active != null ? active : playlists.get(0));
            playlistPreference.setPlaylistList(playlists);
        }

        //3. Tuner settings
        for(Map.Entry<String,String> entry: contents.getRoles().entrySet())
        {
            if(entry.getValue().equals(ROLE_TUNERS))
            {
                writeFile(directoryPreference.getDirectoryConfiguration().resolve(TUNER_CONFIGURATION_FILE),
                    contents.getEntries().get(entry.getKey()));
            }
        }

        //4. Icons and map/color settings
        Path settingsFolder = getSettingsFolder(localProperties);

        for(Map.Entry<String,String> entry: contents.getRoles().entrySet())
        {
            if(entry.getValue().equals(ROLE_ICONS))
            {
                Files.createDirectories(settingsFolder);
                byte[] icons = restoreIconImages(contents, settingsFolder.resolve(ICON_IMAGES_FOLDER),
                    contents.getEntries().get(entry.getKey()));
                writeFile(settingsFolder.resolve(ICONS_FILE), icons);
            }
            else if(entry.getValue().equals(ROLE_SETTINGS))
            {
                Files.createDirectories(settingsFolder);
                writeFile(settingsFolder.resolve(getSettingsFileName(localProperties)),
                    contents.getEntries().get(entry.getKey()));
            }
        }

        //5. Application properties - merged, keeping this computer's folder locations
        for(Map.Entry<String,String> entry: contents.getRoles().entrySet())
        {
            if(entry.getValue().equals(ROLE_PROPERTIES))
            {
                Properties backupProperties = new Properties();
                backupProperties.load(new ByteArrayInputStream(contents.getEntries().get(entry.getKey())));

                for(String key: backupProperties.stringPropertyNames())
                {
                    String value = backupProperties.getProperty(key);

                    if(!key.equals(ROOT_DIRECTORY_PROPERTY) && !ABSOLUTE_PATH.matcher(value).matches())
                    {
                        localProperties.setProperty(key, value);
                    }
                }

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                localProperties.store(out, "SDRTrunk - SDR Trunking Decoder Application Settings");
                writeFile(localPropertiesPath, out.toByteArray());
            }
        }

        //6. JMBE library files - into this computer's JMBE folder
        JmbeLibraryPreference jmbePreference = new JmbeLibraryPreference(type -> {});
        Path jmbeFolder = directoryPreference.getDirectoryJmbe();
        String imbe = manifest.getProperty(MANIFEST_JMBE_IMBE);
        String ambe = manifest.getProperty(MANIFEST_JMBE_AMBE);

        //A backup without an IMBE library leaves this computer's IMBE library as it is
        if(imbe != null)
        {
            jmbePreference.setPathJmbeLibrary(writeJmbe(contents, jmbeFolder, imbe));
        }

        if(ambe != null)
        {
            jmbePreference.setPathJmbeAmbeLibrary(writeJmbe(contents, jmbeFolder, ambe));
        }
        else
        {
            jmbePreference.resetPathJmbeAmbeLibrary();
        }

        prefsRoot.node(PREFERENCES_ROOT).flush();
    }

    private static Path writeJmbe(BackupContents contents, Path folder, String name) throws Exception
    {
        byte[] data = contents.getEntries().get(FILES + "jmbe/" + name);

        if(data == null)
        {
            throw new BackupException("Backup is missing JMBE library [" + name + "]");
        }

        Path destination = folder.resolve(name);

        //Leave an identical existing library file untouched
        if(!(Files.isRegularFile(destination) && MessageDigest.isEqual(Files.readAllBytes(destination), data)))
        {
            writeFile(destination, data);
        }

        return destination;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Startup results
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Before-restore backup path, when a restore was completed at this start (null otherwise).
     */
    public static Path getCompletedRestoreUndoPath()
    {
        return sCompletedRestoreUndoPath;
    }

    /**
     * Indicates if a staged restore could not be used at this start.
     */
    public static boolean isRestoreFailed()
    {
        return sRestoreFailed;
    }

    /**
     * Clears the startup results after they have been shown.
     */
    public static void clearStartupResults()
    {
        sCompletedRestoreUndoPath = null;
        sRestoreFailed = false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private static Path getSettingsFolder(Properties properties)
    {
        String root = properties != null ? properties.getProperty(ROOT_DIRECTORY_PROPERTY, "SDRTrunk") : "SDRTrunk";

        if(root.equalsIgnoreCase("SDRTrunk"))
        {
            return Paths.get(System.getProperty("user.home"), "SDRTrunk").resolve(SETTINGS_FOLDER);
        }

        return Paths.get(root).resolve(SETTINGS_FOLDER);
    }

    private static String getSettingsFileName(Properties properties)
    {
        String name = properties != null ? properties.getProperty(SETTINGS_CURRENT_FILENAME, null) : null;

        if(name == null || name.isBlank())
        {
            name = properties != null ? properties.getProperty("settings.defaultFilename", SETTINGS_DEFAULT_FILENAME) :
                SETTINGS_DEFAULT_FILENAME;
        }

        return Paths.get(name).getFileName().toString();
    }

    /**
     * Writes a file safely: to a temporary file next to the destination, then moved into place.
     */
    private static void writeFile(Path destination, byte[] data) throws IOException
    {
        if(destination.getParent() != null)
        {
            Files.createDirectories(destination.getParent());
        }

        Path temp = destination.resolveSibling(destination.getFileName().toString() + ".restore-partial");
        Files.write(temp, data);

        if(!MessageDigest.isEqual(Files.readAllBytes(temp), data))
        {
            deleteQuietly(temp);
            throw new IOException("Verification failed writing [" + destination + "]");
        }

        moveIntoPlace(temp, destination);
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setAside(Path source, Path target)
    {
        try
        {
            moveIntoPlace(source, target);
        }
        catch(Exception e)
        {
            mLog.error("Unable to move [" + source + "] to [" + target + "]", e);
            deleteQuietly(source);
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            if(path != null)
            {
                Files.deleteIfExists(path);
            }
        }
        catch(Exception e)
        {
            mLog.warn("Unable to delete [" + path + "]");
        }
    }

    private static String uniqueName(String name, List<String> used)
    {
        String candidate = name;
        int counter = 2;

        while(used.contains(candidate))
        {
            candidate = counter++ + "-" + name;
        }

        used.add(candidate);
        return candidate;
    }

    static String sha256(byte[] data)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void checkXml(byte[] data, String name) throws BackupException
    {
        try
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            builder.parse(new ByteArrayInputStream(data));
        }
        catch(Exception e)
        {
            throw new BackupException("File is not readable XML [" + name + "]", e);
        }
    }

    private static void checkJson(byte[] data, String name) throws BackupException
    {
        try
        {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(data);
        }
        catch(Exception e)
        {
            throw new BackupException("File is not readable JSON [" + name + "]", e);
        }
    }

    private static void checkZip(byte[] data, String name) throws BackupException
    {
        try(ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data)))
        {
            if(zip.getNextEntry() == null)
            {
                throw new BackupException("Library file is empty [" + name + "]");
            }

            while(zip.getNextEntry() != null)
            {
                //read through all entries
            }
        }
        catch(BackupException be)
        {
            throw be;
        }
        catch(Exception e)
        {
            throw new BackupException("Library file is not readable [" + name + "]", e);
        }
    }

    /**
     * Verified contents of a backup file.
     */
    public static class BackupContents
    {
        private final Properties mManifest;
        private final Map<String,byte[]> mEntries;
        private final Map<String,String> mRoles;

        BackupContents(Properties manifest, Map<String,byte[]> entries, Map<String,String> roles)
        {
            mManifest = manifest;
            mEntries = entries;
            mRoles = roles;
        }

        public Properties getManifest()
        {
            return mManifest;
        }

        public Map<String,byte[]> getEntries()
        {
            return mEntries;
        }

        public Map<String,String> getRoles()
        {
            return mRoles;
        }

        /**
         * Application version that created the backup.
         */
        public String getApplicationVersion()
        {
            return mManifest.getProperty(MANIFEST_APPLICATION, "unknown");
        }
    }
}
