package com.ashkiano.virtualchest;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.*;

//TODO dodělat chybové hlášky do configu
//TODO název tabulky taky přidat do configu
//TODO opravit bug při přesouvání z horního do spodního inventu
//TODO u všech hlášek udělat podporu hex kódů barev
//TODO přidat permise admina, aby mohl otevírat chesty hráčů a napojit na webovou službu překladu nicku na uuid
public class VirtualChest extends JavaPlugin implements Listener {
    private Connection connection;
    private String host;
    private String database;
    private String username;
    private String password;
    private int port;
    private boolean upload;
    private boolean download;
    // Blocked items
    private final List<Material> blockedItems = new ArrayList<>();

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        Objects.requireNonNull(this.getCommand("transfer")).setExecutor(this);  // register the command
        Bukkit.getServer().getPluginManager().registerEvents(this, this);

        host = this.getConfig().getString("host");
        database = this.getConfig().getString("database");
        username = this.getConfig().getString("username");
        password = this.getConfig().getString("password");
        port = this.getConfig().getInt("port");
        upload = this.getConfig().getBoolean("upload");
        download = this.getConfig().getBoolean("download");
        List<String> blockedItemsConfig = this.getConfig().getStringList("blockedItems");
        for (String item : blockedItemsConfig)
            blockedItems.add(Material.valueOf(item));

        setupDatabase();

        System.out.println("Thank you for using the VirtualChest plugin! If you enjoy using this plugin, please consider making a donation to support the development. You can donate at: https://paypal.me/josefvyskocil");

        Metrics metrics = new Metrics(this, 19170);
    }

    private void setupDatabase() {
        try {
            synchronized (this) {
                if (getConnection() != null && !getConnection().isClosed()) {
                    return;
                }

                Class.forName("com.mysql.jdbc.Driver");
                setConnection(DriverManager.getConnection("jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database, this.username, this.password));
                Bukkit.getConsoleSender().sendMessage("Database Connected!");

                // Schedule a task to keep the connection alive
                Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    try {
                        if (getConnection() != null) {
                            if (getConnection().isClosed()) {
                                setConnection(DriverManager.getConnection("jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database, this.username, this.password));
                                Bukkit.getConsoleSender().sendMessage("Database reconnected!");
                            } else {
                                getConnection().createStatement().executeQuery("/* ping */ SELECT 1");
                            }
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }, 6000L, 6000L); // every 5 minutes

            }
        } catch (SQLException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        try {
            PreparedStatement ps = connection.prepareStatement("CREATE TABLE IF NOT EXISTS virtualchest (uuid TEXT, itemstack BLOB)");
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(ChatColor.BLUE + "Transfer")) {
            ItemStack currentItem = event.getCurrentItem();
            ItemStack cursorItem = event.getCursor();
            Player player = (Player) event.getWhoClicked();

            // Here we also check the item in the cursor
            if (containsBlockedItem(currentItem) || containsBlockedItem(cursorItem) || isShulkerContainsBlockedItem(currentItem) || isShulkerContainsBlockedItem(cursorItem)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "This item cannot be inserted.");
                return;
            }

            if (event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getTopInventory())) {
                //player.sendMessage(ChatColor.RED + "1.");
                switch (event.getAction()) {
                    case PICKUP_ALL:
                    case PICKUP_HALF:
                    case PICKUP_ONE:
                    case PICKUP_SOME:
                    case COLLECT_TO_CURSOR:
                    case MOVE_TO_OTHER_INVENTORY: //TODO možná bug opraví přidat tohle do podmínky níže
                        //player.sendMessage(ChatColor.RED + "2.");
                        // Check if download is allowed
                        if (!download) {
                            //player.sendMessage(ChatColor.RED + "3.");
                            event.setCancelled(true);
                        }
                        break;
                    default:
                        break;
                }
                //TODO do této podmínky přidat ten case výše
            } else if (event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getBottomInventory())) {
                //player.sendMessage(ChatColor.RED + "4.");
                // Check if upload is allowed
                if (!upload) {
                    //player.sendMessage(ChatColor.RED + "5.");
                    event.setCancelled(true);
                }
            }
        }
    }

    private boolean containsBlockedItem(ItemStack item) {
        return item != null && (blockedItems.contains(item.getType()) || isShulkerContainsBlockedItem(item));
    }

    private boolean isShulkerContainsBlockedItem(ItemStack item) {
        if (item != null && item.getType() == Material.SHULKER_BOX) {
            BlockStateMeta blockStateMeta = (BlockStateMeta) item.getItemMeta();
            if (blockStateMeta != null) {
                BlockState blockState = blockStateMeta.getBlockState();
                if (blockState instanceof ShulkerBox) {
                    ShulkerBox shulkerBox = (ShulkerBox) blockState;
                    for (ItemStack shulkerItem : shulkerBox.getInventory().getContents()) {
                        if (shulkerItem != null && blockedItems.contains(shulkerItem.getType())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }


    @Override
    public boolean onCommand(@Nonnull CommandSender sender, Command cmd,@Nonnull String label,@Nonnull String[] args) {
        if (cmd.getName().equalsIgnoreCase("transfer")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used by the player!");
                return true;
            }
            Player player = (Player) sender;
            Inventory inv = Bukkit.createInventory(null, 54, ChatColor.BLUE + "Transfer");

            // Check if the database connection is active
            try {
                if (getConnection() == null || getConnection().isClosed()) {
                    sender.sendMessage(ChatColor.RED + "The database connection is currently unavailable. Please try again later.");
                    return true;
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            // Load items from database
            try {
                PreparedStatement ps = connection.prepareStatement("SELECT itemstack FROM virtualchest WHERE uuid = ?");
                ps.setString(1, player.getUniqueId().toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    byte[] itemBytes = rs.getBytes("itemstack");
                    ItemStack item = fromByteArray(itemBytes);
                    inv.addItem(item);
                }
            } catch (SQLException | IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }

            player.openInventory(inv);
            return true;
        }
        return false;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(ChatColor.BLUE + "Transfer")) {
            try {
                // Delete existing items for the player
                PreparedStatement deleteStatement = connection.prepareStatement("DELETE FROM virtualchest WHERE uuid = ?");
                deleteStatement.setString(1, event.getPlayer().getUniqueId().toString());
                deleteStatement.executeUpdate();
                for (int i = 0; i < event.getInventory().getSize(); i++) {
                    ItemStack item = event.getInventory().getItem(i);
                    if (item != null && !item.getType().equals(Material.AIR)) {
                        PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO virtualchest(uuid, itemstack) VALUES(?, ?)");
                        insertStatement.setString(1, event.getPlayer().getUniqueId().toString());
                        insertStatement.setBytes(2, toByteArray(item));
                        insertStatement.executeUpdate();

                        // Remove the item from the inventory
                        event.getInventory().setItem(i, null);
                    }
                }
            } catch (SQLException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void onDisable() {
        try {
            if (getConnection() != null && !getConnection().isClosed()) {
                getConnection().close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static byte[] toByteArray(ItemStack item) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeObject(item);
            return bos.toByteArray();
        }
    }

    public static ItemStack fromByteArray(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            return (ItemStack) ois.readObject();
        }
    }
}


