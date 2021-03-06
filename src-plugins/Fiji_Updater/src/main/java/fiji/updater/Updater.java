package fiji.updater;

import ij.plugin.PlugIn;

import fiji.updater.logic.Checksummer;
import fiji.updater.logic.PluginCollection;
import fiji.updater.logic.PluginObject;
import fiji.updater.logic.XMLFileDownloader;
import fiji.updater.logic.XMLFileReader;

import fiji.updater.ui.SwingTools;
import fiji.updater.ui.UpdaterFrame;
import fiji.updater.ui.ViewOptions;

import fiji.updater.ui.ViewOptions.Option;

import fiji.updater.ui.ij1.GraphicalAuthenticator;
import fiji.updater.ui.ij1.IJ1UserInterface;

import fiji.updater.util.Canceled;
import fiji.updater.util.Progress;
import fiji.updater.util.UserInterface;
import fiji.updater.util.Util;

import ij.Executer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.net.Authenticator;
import java.net.UnknownHostException;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import javax.xml.parsers.ParserConfigurationException;

public class Updater implements PlugIn {
	public static String MAIN_URL = "http://fiji.sc/update/";
	public static String UPDATE_DIRECTORY = "/var/www/update/";
	public static String SSH_HOST = "fiji.sc";

	public static final String XML_COMPRESSED = "db.xml.gz";

	// Key names for ij.Prefs for saved values
	// Note: ij.Prefs is only saved during shutdown of Fiji
	public static final String PREFS_USER = "fiji.updater.login";

	public static boolean debug, testRun, hidden;

	public void run(String arg) {
		UserInterface.set(new IJ1UserInterface());

		if (errorIfDebian())
			return;

		if (new File(Util.ijRoot, "update").exists()) {
			UserInterface.get().error("Fiji restart required to finalize previous update");
			return;
		}
		Util.useSystemProxies();

		final PluginCollection plugins = new PluginCollection();
		try {
			plugins.read();
		}
		catch (FileNotFoundException e) { /* ignore */ }
		catch (Exception e) {
			e.printStackTrace();
			UserInterface.get().error("There was an error reading the cached metadata: " + e);
			return;
		}

		Authenticator.setDefault(new GraphicalAuthenticator());

		final UpdaterFrame main = new UpdaterFrame(plugins, hidden);
		main.setEasyMode(true);

		Progress progress = main.getProgress("Starting up...");
		XMLFileDownloader downloader = new XMLFileDownloader(plugins);
		downloader.addProgress(progress);
		try {
			downloader.start();
		} catch (Canceled e) {
			downloader.done();
			main.error("Canceled");
			return;
		} catch (Exception e) {
			e.printStackTrace();
			downloader.done();
			String message;
			if (e instanceof UnknownHostException)
				message = "Failed to lookup host "
					+ e.getMessage();
			else
				message = "Download/checksum failed: " + e;
			main.error(message);
			return;
		}

		String warnings = downloader.getWarnings();
		if (!warnings.equals(""))
			main.warn(warnings);

		progress = main.getProgress("Matching with local files...");
		Checksummer checksummer = new Checksummer(plugins, progress);
		try {
			if (debug)
				checksummer.done();
			else
				checksummer.updateFromLocal();
		} catch (Canceled e) {
			checksummer.done();
			main.error("Canceled");
			return;
		}

		PluginObject updater = plugins.getPlugin("plugins/Fiji_Updater.jar");
		if ((updater != null && updater.getStatus() == PluginObject.Status.UPDATEABLE)) {
			if (SwingTools.showQuestion(hidden, main, "Update the updater",
					"There is an update available for the Fiji Updater. Install now?")) {
				// download just the updater
				main.updateTheUpdater();

				// overwrite the original updater
				if (!overwriteWithUpdated(updater))
					main.info("Please restart Fiji and call Help>Update Fiji to continue the update");
				else
					/*
					 * Start a new Thread that refreshes the menus and restarts the updater;
					 * the new updater has to be restarted in another thread to avoid clashes
					 * with the current updater.
					 */
					SwingUtilities.invokeLater(new Runnable() {
						public void run() {
							new Executer("Refresh Menus").run();
							new Executer("Update Fiji", null);
						}
					});
			}
			// we do not save the plugins to prevent the mtime from changing
			return;
		}

		main.setVisible(true);

		if ("update".equals(arg)) {
			plugins.markForUpdate(false);
			main.setViewOption(Option.UPDATEABLE);
			if (testRun)
				System.err.println("forceable updates: "
					+ Util.join(", ",
						plugins.updateable(true))
					+ ", changes: "
					+ Util.join(", ", plugins.changes()));
			else if (plugins.hasForcableUpdates()) {
				main.warn("There are locally modified files!");
				if (plugins.hasUploadableSites() && !plugins.hasChanges()) {
					main.setViewOption(Option
							.LOCALLY_MODIFIED);
					main.setEasyMode(false);
				}
			}
			else if (!plugins.hasChanges())
				main.info("Your Fiji is up to date!");
		}

		main.updatePluginsTable();
	}

	protected static boolean overwriteWithUpdated(PluginObject plugin) {
		File downloaded = new File(Util.prefix("update/" + plugin.filename));
		if (!downloaded.exists())
			return true; // assume all is well if there is no updated file
		File jar = new File(Util.prefix(plugin.filename));
		if (!jar.delete() && !moveOutOfTheWay(jar))
			return false;
		if (!downloaded.renameTo(jar))
			return false;
		for (;;) {
			downloaded = downloaded.getParentFile();
			if (downloaded == null)
				return true;
			String[] list = downloaded.list();
			if (list != null && list.length > 0)
				return true;
			// dir is empty, remove
			if (!downloaded.delete())
				return false;
		}
	}

	/** This returns true if this seems to be the Debian packaged
	 * version of Fiji, or false otherwise. */

	public static boolean isDebian() {
		String debianProperty = System.getProperty("fiji.debian");
		return debianProperty != null && debianProperty.equals("true");
	}

	/** If this seems to be the Debian packaged version of Fiji,
	 * then produce an error and return true.  Otherwise return false. */

	public static boolean errorIfDebian() {
		// If this is the Debian / Ubuntu packaged version, then
		// insist that the user uses apt-get / synaptic instead:
		if (isDebian()) {
			String message = "You are using the Debian packaged version of Fiji.\n";
			message += "You should update Fiji with your system's usual package manager instead.";
			UserInterface.get().error(message);
			return true;
		} else
			return false;
	}

	protected static boolean moveOutOfTheWay(File file) {
		if (!file.exists())
			return true;
		File backup = new File(file.getParentFile(), file.getName() + ".old");
		if (backup.exists() && !backup.delete()) {
			int i = 2;
			for (;;) {
				backup = new File(file.getParentFile(), file.getName() + ".old" + i);
				if (!backup.exists())
					break;
			}
		}
		return file.renameTo(backup);
	}
}