package dev.vibesindev.vibesmusic.gui;

import dev.vibesindev.vibesmusic.VibesInDevPlugin;
import dev.vibesindev.vibesmusic.party.MusicParty;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PartyInfoMenu implements Listener {

    private static final String INFO_TITLE =
            ChatColor.DARK_PURPLE + "Music Party";

    private static final String CONFIRM_TITLE =
            ChatColor.DARK_RED + "Manage Party Member";

    private final VibesInDevPlugin plugin;

    public PartyInfoMenu(VibesInDevPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        MusicParty party = plugin.parties()
                .getPartyByPlayer(player.getUniqueId());

        if (party == null) {
            player.sendMessage(ChatColor.YELLOW + "You are not in a party.");
            return;
        }

        Inventory inventory = Bukkit.createInventory(
                new PartyInfoHolder(),
                54,
                INFO_TITLE
        );

        fill(inventory);

        inventory.setItem(4, item(
                Material.NOTE_BLOCK,
                ChatColor.LIGHT_PURPLE + "Party Information",
                List.of(
                        ChatColor.GRAY + "Owner: " + ChatColor.WHITE + playerName(party.getOwner()),
                        ChatColor.GRAY + "Members: " + ChatColor.WHITE + party.getMemberCount(),
                        ChatColor.GRAY + "Queue: " + ChatColor.WHITE + party.getQueueSize(),
                        ChatColor.GRAY + "Shuffle: " + ChatColor.WHITE + (party.isShuffle() ? "On" : "Off"),
                        ChatColor.GRAY + "Repeat: " + ChatColor.WHITE + party.getRepeatMode().name()
                )
        ));

        int[] slots = {
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };

        List<UUID> members = party.getMembers();
        for (int i = 0; i < members.size() && i < slots.length; i++) {
            UUID member = members.get(i);
            boolean owner = party.isOwner(member);
            Player online = Bukkit.getPlayer(member);

            List<String> lore = new ArrayList<>();
            lore.add(owner
                    ? ChatColor.GOLD + "Party Owner"
                    : ChatColor.GRAY + "Party Member");

            if (online != null && online.isOnline()) {
                lore.add(ChatColor.GREEN + "Online");
            } else {
                lore.add(ChatColor.RED + "Offline");
            }

            if (party.isOwner(player.getUniqueId()) && !owner) {
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click to manage");
            }

            inventory.setItem(
                    slots[i],
                    playerHead(
                            member,
                            ChatColor.WHITE + playerName(member),
                            lore
                    )
            );
        }

        inventory.setItem(49, item(
                Material.BARRIER,
                ChatColor.RED + "Close",
                List.of(ChatColor.GRAY + "Close this menu")
        ));

        player.openInventory(inventory);
    }

    private void openConfirmation(Player player, UUID target) {
        MusicParty party = plugin.parties()
                .getPartyByPlayer(player.getUniqueId());

        if (party == null || !party.isOwner(player.getUniqueId())
                || !party.hasMember(target) || party.isOwner(target)) {
            player.sendMessage(ChatColor.RED + "You cannot manage that player.");
            return;
        }

        Inventory inventory = Bukkit.createInventory(
                new PartyConfirmHolder(target),
                27,
                CONFIRM_TITLE
        );

        fill(inventory);

        inventory.setItem(11, item(
                Material.RED_WOOL,
                ChatColor.RED + "Kick Player",
                List.of(
                        ChatColor.GRAY + "Remove " + ChatColor.WHITE + playerName(target),
                        ChatColor.GRAY + "from the music party."
                )
        ));

        inventory.setItem(13, playerHead(
                target,
                ChatColor.WHITE + playerName(target),
                List.of(ChatColor.GRAY + "Selected party member")
        ));

        inventory.setItem(15, item(
                Material.GOLD_BLOCK,
                ChatColor.GOLD + "Transfer Ownership",
                List.of(
                        ChatColor.GRAY + "Make " + ChatColor.WHITE + playerName(target),
                        ChatColor.GRAY + "the new party owner."
                )
        ));

        inventory.setItem(22, item(
                Material.ARROW,
                ChatColor.YELLOW + "Back",
                List.of(ChatColor.GRAY + "Return to party info")
        ));

        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();

        if (!(holder instanceof PartyInfoHolder)
                && !(holder instanceof PartyConfirmHolder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getRawSlot() >= top.getSize()) {
            return;
        }

        if (holder instanceof PartyInfoHolder) {
            handleInfoClick(player, event.getRawSlot());
        } else if (holder instanceof PartyConfirmHolder confirm) {
            handleConfirmClick(player, confirm.target(), event.getRawSlot());
        }
    }

    private void handleInfoClick(Player player, int slot) {
        if (slot == 49) {
            player.closeInventory();
            return;
        }

        int[] slots = {
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };

        MusicParty party = plugin.parties()
                .getPartyByPlayer(player.getUniqueId());

        if (party == null || !party.isOwner(player.getUniqueId())) {
            return;
        }

        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != slot || i >= party.getMembers().size()) {
                continue;
            }

            UUID target = party.getMembers().get(i);
            if (!party.isOwner(target)) {
                openConfirmation(player, target);
            }
            return;
        }
    }

    private void handleConfirmClick(Player player, UUID target, int slot) {
        if (slot == 22) {
            open(player);
            return;
        }

        MusicParty party = plugin.parties()
                .getPartyByPlayer(player.getUniqueId());

        if (party == null || !party.isOwner(player.getUniqueId())
                || !party.hasMember(target) || party.isOwner(target)) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "That party member is no longer available.");
            return;
        }

        if (slot == 11) {
            Player targetPlayer = Bukkit.getPlayer(target);

            if (!plugin.parties().kickMember(player.getUniqueId(), target)) {
                player.sendMessage(ChatColor.RED + "Could not kick that player.");
                return;
            }

            if (targetPlayer != null && targetPlayer.isOnline()
                    && plugin.network().isSupported(targetPlayer)) {
                plugin.network().stop(targetPlayer);
            }

            notifyMembers(party, ChatColor.YELLOW + player.getName()
                    + " removed " + ChatColor.AQUA + playerName(target)
                    + ChatColor.YELLOW + " from the party.");

            if (targetPlayer != null && targetPlayer.isOnline()) {
                targetPlayer.sendMessage(ChatColor.RED + "You were removed from the music party.");
            }

            player.closeInventory();
            return;
        }

        if (slot == 15) {
            if (!plugin.parties().transferOwnership(player.getUniqueId(), target)) {
                player.sendMessage(ChatColor.RED + "Could not transfer party ownership.");
                return;
            }

            notifyMembers(party, ChatColor.GREEN + playerName(target)
                    + " is now the party owner.");

            Player targetPlayer = Bukkit.getPlayer(target);
            if (targetPlayer != null && targetPlayer.isOnline()) {
                targetPlayer.sendMessage(ChatColor.GREEN + "You are now the party owner.");
            }

            player.closeInventory();
        }
    }

    private void notifyMembers(MusicParty party, String message) {
        for (UUID member : party.getMembers()) {
            Player target = Bukkit.getPlayer(member);
            if (target != null && target.isOnline()) {
                target.sendMessage(message);
            }
        }
    }

    private String playerName(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getName() : uuid.toString();
    }

    private void fill(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack playerHead(UUID uuid, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta != null) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                meta.setOwningPlayer(player);
            }
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static final class PartyInfoHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record PartyConfirmHolder(UUID target) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
