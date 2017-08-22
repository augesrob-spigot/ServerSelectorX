package xyz.derkades.serverselectorx;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentWrapper;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.inventivetalent.update.spiget.SpigetUpdate;
import org.inventivetalent.update.spiget.UpdateCallback;
import org.inventivetalent.update.spiget.comparator.VersionComparator;

import xyz.derkades.derkutils.Cooldown;
import xyz.derkades.derkutils.bukkit.Colors;
import xyz.derkades.derkutils.caching.Cache;
import xyz.derkades.serverselectorx.placeholders.Placeholders;
import xyz.derkades.serverselectorx.placeholders.PlaceholdersDisabled;
import xyz.derkades.serverselectorx.placeholders.PlaceholdersEnabled;

public class Main extends JavaPlugin {
	
	private static final int CONFIG_VERSION = 7;
	
	public static final int GLOWING_ENCHANTMENT_ID = 96;
	
	public static Placeholders PLACEHOLDER_API;
	
	private static Plugin plugin;
	
	public static Plugin getPlugin(){
		return plugin;
	}
	
	@Override
	public void onEnable(){
		plugin = this;

		//Register listeners
		Bukkit.getPluginManager().registerEvents(new SelectorOpenListener(), this);
		Bukkit.getPluginManager().registerEvents(new OnJoinListener(), this);
		Bukkit.getPluginManager().registerEvents(new ItemMoveDropCancelListener(), this);
		
		List<String> offHandVersions = Arrays.asList("1.9", "1.10", "1.11", "1.12");
		for (String version : offHandVersions) {
			if (Bukkit.getBukkitVersion().contains(version)) {
				Bukkit.getPluginManager().registerEvents(new OffHandMoveCancel(), this);
			}
		}
		
		//Register outgoing channel
		getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
		
		//Register command
		getCommand("serverselectorx").setExecutor(new ReloadCommand());
		
		//Start bStats
		startMetrics();
		
		//Check if config is up to date
		int version = getConfig().getInt("version");
		if (version != CONFIG_VERSION){
			Logger logger = super.getLogger();
			logger.log(Level.SEVERE, "************** IMPORTANT **************");
			logger.log(Level.SEVERE, "You updated the plugin without deleting the config.");
			logger.log(Level.SEVERE, "Please rename config.yml to something else and restart your server.");
			logger.log(Level.SEVERE, "If you don't want to redo your config, see resource updates on spigotmc.org for instructions.");
			logger.log(Level.SEVERE, "***************************************");
			getServer().getPluginManager().disablePlugin(this);
		}
		
		//Register custom selector commands
		registerCommands();
		
		//Register glowing enchantment
		registerEnchantment();
		
		//Check if PlaceHolderAPI is installed
		if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")){
			Main.PLACEHOLDER_API = new PlaceholdersEnabled();
			getLogger().log(Level.INFO, "PlaceholderAPI is found. Placeholders will work!");
		} else {
			Main.PLACEHOLDER_API = new PlaceholdersDisabled();
			getLogger().log(Level.INFO, "PlaceholderAPI is not installed. The plugin will still work.");
		}
		
		//Periodically clean cache
		getServer().getScheduler().runTaskTimer(this, () -> {
			Cache.cleanCache();
		}, 30*60*20, 30*60*20);
		
		//Check for updates asynchronously
		getServer().getScheduler().runTaskAsynchronously(this, () -> {
			checkForUpdates();
		});
		
		if (getConfig().getBoolean("background-pinging", true)) {
			new PingServersBackground().runTaskTimerAsynchronously(this, 50, 5);
		}
	}
	
	private void startMetrics() {
		final Metrics metrics = new Metrics(this);
		
		metrics.addCustomChart(new Metrics.SimplePie("placeholderapi", () -> {
			if (Main.PLACEHOLDER_API instanceof PlaceholdersEnabled) {
				return "yes";
			} else {
				return "no";
			}
		}));
		
		metrics.addCustomChart(new Metrics.SimplePie("number_of_selectors", () -> {
			return Main.getServerSelectorConfigurationFiles().size() + "";
		}));
		
		metrics.addCustomChart(new Metrics.AdvancedPie("selector_item", () -> {
			final Map<String, Integer> map = new HashMap<>();
			
			for (final FileConfiguration config : Main.getServerSelectorConfigurationFiles()) {
				final Material material = Material.getMaterial(config.getString("item"));
				if (material == null) continue; //Do not count invalid items
				
				if (map.containsKey(material.toString())) {
					map.put(material.toString(), map.get(material.toString() + 1));
				} else {
					map.put(material.toString(), 1);
				}
			}
			
			return map;
		}));
		
		metrics.addCustomChart(new Metrics.AdvancedPie("type", () -> {
			final Map<String, Integer> map = new HashMap<>();
			
			for (final FileConfiguration config : Main.getServerSelectorConfigurationFiles()) {
				for (final String slot : config.getConfigurationSection("menu").getKeys(false)) {
					String action = config.getString("menu." + slot + ".action").split(":")[0];
					if (map.containsKey(action)) {
						map.put(action, map.get(action) + 1);
					} else {
						map.put(action, 1);
					}
				}
			}
			
			return map;
		}));
	}
	
	public void reloadConfig(){	
		//Load default config if it has been deleted
		super.saveDefaultConfig();
		
		//Reload config
		super.reloadConfig();
	
		//Copy custom config if it does not exist
		File file = new File(this.getDataFolder() + "/menu", "default.yml");
		if (!file.exists()){
			URL inputUrl = getClass().getResource("/xyz/derkades/serverselectorx/default-selector.yml");
			try {
				FileUtils.copyURLToFile(inputUrl, file);
			} catch (IOException e){
				e.printStackTrace();
			}
		}
		
		//Initialize variables
		ItemMoveDropCancelListener.DROP_PERMISSION_ENABLED = getConfig().getBoolean("cancel-item-drop", false);
		ItemMoveDropCancelListener.MOVE_PERMISSION_ENABLED = getConfig().getBoolean("cancel-item-move", false);
	}
	
	/**
	 * Registers all custom commands by going through all menu files and adding commands
	 */
	private void registerCommands(){
		try {
			final Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
	
			bukkitCommandMap.setAccessible(true);
			CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());
			
			for (FileConfiguration config : Main.getServerSelectorConfigurationFiles()){
				String commandName = config.getString("command");
				
				if (commandName == null || commandName.equalsIgnoreCase("none")) {
					continue;
				}
				
				commandMap.register("ssx-custom", new Command(commandName){
	
					@Override
					public boolean execute(CommandSender sender, String label, String[] args) {
						if (sender instanceof Player){
							Player player = (Player) sender;
							Main.openSelector(player, config);
						}
						return true;
					}
					
				});
	
			}
		} catch (NoSuchFieldException | IllegalAccessException e) {
			e.printStackTrace();
		}
	}
	
	private void registerEnchantment(){
		try {
			Field f = Enchantment.class.getDeclaredField("acceptingNew");
			f.setAccessible(true);
			f.set(null, true);
			
			EnchantmentWrapper.registerEnchantment(new GlowEnchantment());
		} catch (NoSuchFieldException | IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e){
			//Already registered (or another enchantment exists with this id?)
			getLogger().warning("Error registering enchantment. If this is not caused by /reload, please report this problem.");
			e.printStackTrace();
		}
	}
	
	public static FileConfiguration getSelectorConfigurationFile(String name){
		File file = new File(Main.getPlugin().getDataFolder() + "/menu", name + ".yml");
		if (file.exists()){
			return YamlConfiguration.loadConfiguration(file);
		} else {
			return null;
		}
	}
	
	public static List<FileConfiguration> getServerSelectorConfigurationFiles(){
		List<FileConfiguration> configs = new ArrayList<>();
		for (File file : new File(Main.getPlugin().getDataFolder() + "/menu").listFiles()){
			if (!file.getName().endsWith(".yml"))
				continue;

			configs.add(YamlConfiguration.loadConfiguration(file));
		}
		return configs;
	}
	
	public static void openSelector(Player player, FileConfiguration config) {
		long cooldown = Cooldown.getCooldown(config.getName() + player.getName());
		if (cooldown > 0) {
			String cooldownMessage = Main.getPlugin().getConfig().getString("cooldown-message", "&cYou cannot use this yet, please wait {x} seconds.");
			cooldownMessage = cooldownMessage.replace("{x}", String.valueOf((cooldown / 1000) + 1));
			cooldownMessage = Colors.parseColors(cooldownMessage);
			if (!(cooldownMessage.equals("") || cooldownMessage.equals(" "))) { //Do not send message if message is an empty string
				player.sendMessage(cooldownMessage);
			}
			
			return;
		}
		
		long cooldownDuration = Main.getPlugin().getConfig().getLong("selector-open-cooldown", 1000);		
		Cooldown.addCooldown(config.getName() + player.getName(), cooldownDuration);
		
		final boolean permissionsEnabled = Main.getPlugin().getConfig().getBoolean("permissions-enabled");
		final boolean hasPermission = player.hasPermission("ssx.use." + config.getName().replace(".yml", ""));
		if (!permissionsEnabled || hasPermission){
			
			//Play sound
			String soundString = Main.getPlugin().getConfig().getString("selector-open-sound");
			if (soundString != null && !soundString.equals("NONE")){
				try {
					Sound sound = Sound.valueOf(soundString);
					player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
				} catch (IllegalArgumentException e){
					Main.getPlugin().getLogger().log(Level.WARNING, "A sound with the name " + soundString + " could not be found. Make sure that it is the right name for your server version.");
				}
			}
			
			new SelectorMenu(player, config).open();
		} else if (config.getBoolean("no-permission-message-enabled", false)) {
			player.sendMessage(config.getString("no-permission-message"));
			return;
		}
	}
	
	public static void teleportPlayerToServer(final Player player, final String server){
		if (Cooldown.getCooldown("servertp" + player.getName() + server) > 0) {
			return;
		}
		
		Cooldown.addCooldown("servertp" + player.getName() + server, 1000);
		
		if (Main.getPlugin().getConfig().getBoolean("server-teleport-message-enabled", false)){
			if (Main.getPlugin().getConfig().getBoolean("chat-clear", false)){
				for (int i = 0; i < 20; i++){ //Send just 20 lines, because clearing the entire chat is unnecessary.
					player.sendMessage("");
				}
			}
			
			String message = Colors.parseColors(Main.getPlugin().getConfig().getString("server-teleport-message", "error"));
			player.sendMessage(message.replace("{x}", server));
		}

		try (
				ByteArrayOutputStream baos = new ByteArrayOutputStream(); 
				DataOutputStream dos = new DataOutputStream(baos)
			){
			
	        dos.writeUTF("Connect");
	        dos.writeUTF(server);
	        player.sendPluginMessage(getPlugin(), "BungeeCord", baos.toByteArray());
		} catch (IOException e){
			e.printStackTrace();
		}
	}
	
	public static boolean UPDATE_AVAILABLE;
	public static String NEW_VERSION;
	public static String CURRENT_VERSION;
	public static String DOWNLOAD_LINK = "https://www.spigotmc.org/resources/serverselectorx.32853/updates";
	
	private void checkForUpdates() {		
		SpigetUpdate updater = new SpigetUpdate(this, 32853).setVersionComparator(VersionComparator.EQUAL);
		
		updater.checkForUpdate(new UpdateCallback() {
			
			@Override
			public void updateAvailable(String newVersion, String downloadUrl, boolean hasDirectDownload) {
				UPDATE_AVAILABLE = true;
				NEW_VERSION = newVersion;
				CURRENT_VERSION = Main.this.getDescription().getVersion();
				//DOWNLOAD_LINK = downloadUrl;
				
				getLogger().info("An update is available!");
				getLogger().info("Your version: " + CURRENT_VERSION);
				getLogger().info("Latest version: " + NEW_VERSION);
			}

			@Override
			public void upToDate() {
				UPDATE_AVAILABLE = false;
				
				getLogger().info("You are running the latest version.");
			}
		});
	}

}
