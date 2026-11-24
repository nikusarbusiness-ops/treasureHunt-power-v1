package de.powersmp.treasurehunt;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class TreasureHuntPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Connection db;
    private NamespacedKey ticketKey;
    private final Set<Material> ores = EnumSet.noneOf(Material.class);
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private boolean active;
    private int multiplier = 1;
    private long hourEnd;
    private BukkitTask ticker;
    private File messagesFile;
    private org.bukkit.configuration.file.FileConfiguration messages;
    private final Random random = new Random();

    @Override public void onEnable() {
        saveDefaultConfig();
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) saveResource("messages.yml", false);
        messages = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(messagesFile);
        ticketKey = new NamespacedKey(this, "power_ticket");
        loadOres(); openDatabase(); loadState();
        Objects.requireNonNull(getCommand("treasurehunt")).setExecutor(this); getCommand("treasurehunt").setTabCompleter(this);
        Objects.requireNonNull(getCommand("treasure")).setExecutor(this); getCommand("treasure").setTabCompleter(this);
        Objects.requireNonNull(getCommand("powerticket")).setExecutor(this); getCommand("powerticket").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("event.enabled-on-startup")) active = true;
        startTicker();
        for (Player p : Bukkit.getOnlinePlayers()) updateBar(p);
    }
    @Override public void onDisable() { saveState(); if (ticker != null) ticker.cancel(); bars.values().forEach(BossBar::removeAll); bars.clear(); try { if (db != null) db.close(); } catch (SQLException ignored) {} }

    private void loadOres() { ores.clear(); for (String s : getConfig().getStringList("event.ores")) try { ores.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException ex) { getLogger().warning("Unbekanntes Erzmaterial: " + s); } }
    private void openDatabase() {
        try { if (!getDataFolder().exists()) getDataFolder().mkdirs(); db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "treasurehunt.db"));
            try (Statement st = db.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, ores INTEGER DEFAULT 0, coal INTEGER DEFAULT 0, iron INTEGER DEFAULT 0, gold INTEGER DEFAULT 0, diamond INTEGER DEFAULT 0, emerald INTEGER DEFAULT 0, redstone INTEGER DEFAULT 0, lapis INTEGER DEFAULT 0, copper INTEGER DEFAULT 0, nether INTEGER DEFAULT 0, money_count INTEGER DEFAULT 0, money_total INTEGER DEFAULT 0, tools INTEGER DEFAULT 0, spawners INTEGER DEFAULT 0, tickets_found INTEGER DEFAULT 0, tickets_redeemed INTEGER DEFAULT 0, daily_claims INTEGER DEFAULT 0, rarest TEXT DEFAULT 'NONE', last_found TEXT DEFAULT 'NONE', rank_until INTEGER DEFAULT 0, ticket_redeemed INTEGER DEFAULT 0, ticket_start INTEGER DEFAULT 0)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS placed_blocks(world TEXT,x INTEGER,y INTEGER,z INTEGER,PRIMARY KEY(world,x,y,z))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS claims(uuid TEXT,day INTEGER,PRIMARY KEY(uuid,day))");
            }
        } catch (SQLException e) { throw new IllegalStateException("SQLite konnte nicht geöffnet werden", e); }
    }
    private void loadState() { active = getConfig().getBoolean("runtime.active", active); multiplier = Math.max(1, getConfig().getInt("runtime.multiplier", 1)); hourEnd = getConfig().getLong("runtime.hour-end", 0); if (hourEnd > 0 && System.currentTimeMillis() >= hourEnd) { hourEnd = 0; multiplier = 1; } }
    private void saveState() { getConfig().set("runtime.active", active); getConfig().set("runtime.multiplier", multiplier); getConfig().set("runtime.hour-end", hourEnd); saveConfig(); }
    private void startTicker() { ticker = Bukkit.getScheduler().runTaskTimer(this, () -> {
        if (hourEnd > 0 && System.currentTimeMillis() >= hourEnd) endHour(true);
        for (Player p : Bukkit.getOnlinePlayers()) updateBar(p);
    }, 20L, 1200L); }
    private void updateBar(Player p) {
        if (!active) { BossBar old = bars.remove(p.getUniqueId()); if (old != null) old.removePlayer(p); return; }
        BossBar bar = bars.computeIfAbsent(p.getUniqueId(), id -> Bukkit.createBossBar("", parseColor(getConfig().getString("event.bossbar.color", "YELLOW")), parseStyle(getConfig().getString("event.bossbar.style", "SOLID"))));
        String title;
        if (hourEnd > 0) { long left = Math.max(0, hourEnd - System.currentTimeMillis()); title = getConfig().getString("event.bossbar.hour", "✦ TREASURE HOUR x%multiplier% ✦ | Noch %time%").replace("%multiplier%", String.valueOf(multiplier)).replace("%time%", formatDuration(left)); bar.setProgress(Math.max(0, Math.min(1, (double) left / Math.max(1, getConfig().getLong("runtime.hour-duration", left))))); }
        else { title = getConfig().getString("event.bossbar.normal", "✦ TREASURE HUNT ✦ | Baue Erze ab und finde legendäre Schätze!"); bar.setProgress(1.0); }
        bar.setTitle(color(title)); if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
    }
    private BarColor parseColor(String s) { try { return BarColor.valueOf(s.toUpperCase(Locale.ROOT)); } catch (Exception e) { return BarColor.YELLOW; } }
    private BarStyle parseStyle(String s) { try { return BarStyle.valueOf(s.toUpperCase(Locale.ROOT)); } catch (Exception e) { return BarStyle.SOLID; } }
    private String formatDuration(long ms) { Duration d = Duration.ofMillis(ms); long h=d.toHours(), m=d.toMinutesPart(); return String.format(Locale.ROOT, "%d Stunde(n) %02d Minuten", h, m); }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String msg(String key) { return color(messages.getString(key, "&cFehlende Nachricht: " + key)); }
    private void broadcast(String key) { Bukkit.broadcastMessage(msg(key)); }

    @EventHandler public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTask(this, () -> updateBar(e.getPlayer())); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { BossBar b=bars.remove(e.getPlayer().getUniqueId()); if (b!=null) b.removePlayer(e.getPlayer()); }
    @EventHandler public void onPlace(BlockPlaceEvent e) {
        if (!getConfig().getBoolean("event.prevent-placed-ore-farming", true) || !ores.contains(e.getBlockPlaced().getType())) return;
        Block b=e.getBlockPlaced(); try (PreparedStatement ps=db.prepareStatement("INSERT OR IGNORE INTO placed_blocks VALUES(?,?,?,?)")) { ps.setString(1,b.getWorld().getName()); ps.setInt(2,b.getX()); ps.setInt(3,b.getY()); ps.setInt(4,b.getZ()); ps.executeUpdate(); } catch(SQLException ex){ getLogger().warning("Placed-block save failed: "+ex.getMessage()); }
    }
    @EventHandler(ignoreCancelled=true, priority=EventPriority.MONITOR) public void onBreak(BlockBreakEvent e) {
        if (!active || !ores.contains(e.getBlock().getType())) return;
        Block b=e.getBlock(); boolean placed=false;
        try (PreparedStatement ps=db.prepareStatement("DELETE FROM placed_blocks WHERE world=? AND x=? AND y=? AND z=?")) { ps.setString(1,b.getWorld().getName()); ps.setInt(2,b.getX()); ps.setInt(3,b.getY()); ps.setInt(4,b.getZ()); placed=ps.executeUpdate()>0; } catch(SQLException ex){ getLogger().warning("Placed-block lookup failed: "+ex.getMessage()); }
        if (placed) return;
        Player p=e.getPlayer(); incrementOre(p.getUniqueId(), b.getType());
        Reward reward=rollReward(); if (reward!=null) Bukkit.getScheduler().runTask(this, () -> giveReward(p,reward));
    }
    private void incrementOre(UUID id, Material m) { String col=m.name().contains("COAL")?"coal":m.name().contains("IRON")?"iron":m.name().contains("GOLD")?"gold":m.name().contains("DIAMOND")?"diamond":m.name().contains("EMERALD")?"emerald":m.name().contains("REDSTONE")?"redstone":m.name().contains("LAPIS")?"lapis":m.name().contains("COPPER")?"copper":"nether"; update(id,"ores=ores+1,"+col+"="+col+"+1"); }
    private enum Reward { MONEY, TOOL, SPAWNER, TICKET }
    private Reward rollReward() {
        double scale=multiplier; double[] probs={scale/getConfig().getDouble("chances.money",1000),scale/getConfig().getDouble("chances.powertool",40000),scale/getConfig().getDouble("chances.spawner",100000),scale/getConfig().getDouble("chances.ticket",150000)};
        double total=Arrays.stream(probs).sum(); if (total>1) { getLogger().warning("Fundchancen ergeben zusammen über 100%; Roll wird normalisiert."); for(int i=0;i<4;i++) probs[i]/=total; }
        double r=random.nextDouble(), edge=0; Reward[] values=Reward.values(); for(int i=0;i<4;i++){ edge+=probs[i]; if(r<edge)return values[i]; } return null;
    }
    private void giveReward(Player p, Reward reward) {
        String detail;
        switch(reward) {
            case MONEY -> { int min=getConfig().getInt("money.min",1000), max=getConfig().getInt("money.max",50000); int amount=ThreadLocalRandom.current().nextInt(Math.min(min,max),Math.max(min,max)+1); boolean ok=dispatch(getConfig().getString("money.command"),p,"amount",String.valueOf(amount)); if(!ok)return; update(p.getUniqueId(),"money_count=money_count+1,money_total=money_total+"+amount); detail="+"+amount+"$ gefunden!"; animate(p,"money","GELD GEFUNDEN!",detail); setLast(p,"MONEY"); }
            case TOOL -> { List<String> list=getConfig().getStringList("powertools.items"); if(list.isEmpty())return; String tool=list.get(random.nextInt(list.size())); if(!dispatch(getConfig().getString("powertools.command"),p,"tool",tool))return; update(p.getUniqueId(),"tools=tools+1"); detail="Du hast ein PowerTool gefunden: "+tool; animate(p,"powertool","POWERTOOL GEFUNDEN!",detail); setLast(p,"POWERTOOL"); }
            case SPAWNER -> { List<String> list=getConfig().getStringList("spawners.types"); if(list.isEmpty())return; String type=list.get(random.nextInt(list.size())); if(!dispatch(getConfig().getString("spawners.command"),p,"type",type))return; update(p.getUniqueId(),"spawners=spawners+1"); detail="Du hast einen "+type+"-Spawner gefunden!"; animate(p,"spawner","EPISCHER FUND!",detail); setLast(p,"SPAWNER"); }
            case TICKET -> { ItemStack item=createTicket(); Map<Integer,ItemStack> leftover=p.getInventory().addItem(item); if(!leftover.isEmpty()) { p.getWorld().dropItemNaturally(p.getLocation(),item); } update(p.getUniqueId(),"tickets_found=tickets_found+1"); detail="Du hast ein POWER TICKET gefunden!"; animate(p,"ticket","✦ LEGENDÄRER FUND! ✦",detail); Bukkit.broadcastMessage(color("&6✦ LEGENDÄRER FUND! &e"+p.getName()+" &6hat ein POWER TICKET gefunden!")); setLast(p,"POWER_TICKET"); }
        }
    }
    private boolean dispatch(String template, Player p, String key, String value) { if(template==null||template.isBlank()) { getLogger().warning("Reward command is empty; reward not marked as issued."); return false; } String cmd=template.replace("%player%",p.getName()).replace("%"+key+"%",value).replace("%group%",getConfig().getString("ticket.rank-group","power")); boolean ok=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd); if(!ok)getLogger().warning("Reward command returned false: /"+cmd); return ok; }
    private void animate(Player p,String section,String title,String subtitle) { String sound=getConfig().getString("animations."+section+".sound","ENTITY_PLAYER_LEVELUP"), particle=getConfig().getString("animations."+section+".particles","HAPPY_VILLAGER"); try{p.playSound(p.getLocation(),Sound.valueOf(sound),1.2f,1f);}catch(Exception ignored){} try{p.getWorld().spawnParticle(Particle.valueOf(particle),p.getLocation().add(0,1,0),section.equals("ticket")?100:35,.7,.8,.7,.1);}catch(Exception ignored){} p.sendTitle(color("&6&l"+title),color("&e"+subtitle),5,section.equals("money")?35:65,15); p.sendActionBar(color("&6"+subtitle)); }
    private void setLast(Player p,String val){ update(p.getUniqueId(),"last_found='"+val+"', rarest=CASE WHEN '"+val+"'='POWER_TICKET' THEN 'POWER_TICKET' WHEN rarest='NONE' THEN '"+val+"' ELSE rarest END"); }
    private void update(UUID id,String assignments) { try(PreparedStatement ps=db.prepareStatement("INSERT OR IGNORE INTO players(uuid) VALUES(?)")){ps.setString(1,id.toString());ps.executeUpdate();}catch(SQLException ignored){} try(Statement st=db.createStatement()){st.executeUpdate("UPDATE players SET "+assignments+" WHERE uuid='"+id+"'");}catch(SQLException e){getLogger().warning("Stats update failed: "+e.getMessage());} }
    private ItemStack createTicket() { Material mat; try{mat=Material.valueOf(getConfig().getString("ticket.material","NETHER_STAR"));}catch(Exception e){mat=Material.NETHER_STAR;} ItemStack it=new ItemStack(mat); ItemMeta meta=it.getItemMeta(); meta.setDisplayName(color(getConfig().getString("ticket.name","&6&lPOWER TICKET"))); List<String> lore=new ArrayList<>(); for(String s:getConfig().getStringList("ticket.lore"))lore.add(color(s)); meta.setLore(lore); meta.getPersistentDataContainer().set(ticketKey,PersistentDataType.STRING,UUID.randomUUID().toString()); it.setItemMeta(meta); return it; }
    private int ticketCount(Player p) { int n=0; for(ItemStack it:p.getInventory().getContents())if(isTicket(it))n+=it.getAmount(); return n; }
    private boolean isTicket(ItemStack it) { return it!=null&&it.hasItemMeta()&&it.getItemMeta().getPersistentDataContainer().has(ticketKey,PersistentDataType.STRING); }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(command.getName().equalsIgnoreCase("treasurehunt")) {
            if(args.length==0){sender.sendMessage("/treasurehunt start|stop|reload|status|stats|hour");return true;} String sub=args[0].toLowerCase(Locale.ROOT);
            if(sub.equals("start")){if(!sender.hasPermission("treasurehunt.start"))return noPerm(sender);active=true;saveState();broadcast("started");for(Player p:Bukkit.getOnlinePlayers())updateBar(p);return true;}
            if(sub.equals("stop")){if(!sender.hasPermission("treasurehunt.stop"))return noPerm(sender);active=false;endHour(false);saveState();broadcast("stopped");for(Player p:Bukkit.getOnlinePlayers())updateBar(p);return true;}
            if(sub.equals("reload")){if(!sender.hasPermission("treasurehunt.reload"))return noPerm(sender);reloadConfig();loadOres();messages=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(messagesFile);sender.sendMessage("§aKonfiguration neu geladen.");return true;}
            if(sub.equals("status")){sender.sendMessage("§6TreasureHunt: "+(active?"§aAktiv":"§cInaktiv")+" §7| Hour: x"+multiplier);return true;}
            if(sub.equals("stats")){if(!sender.hasPermission("treasurehunt.stats"))return noPerm(sender);globalStats(sender);return true;}
            if(sub.equals("hour")){if(!sender.hasPermission("treasurehunt.hour"))return noPerm(sender);if(args.length>1&&args[1].equalsIgnoreCase("stop")){endHour(true);return true;}if(!active){sender.sendMessage(msg("not-active"));return true;}if(args.length<3){sender.sendMessage("§c/treasurehunt hour <2|3> <30m|1h>");return true;}try{int mult=Integer.parseInt(args[1]);long seconds=parseTime(args[2]);if((mult!=2&&mult!=3)||seconds<=0||seconds>getConfig().getLong("hour.max-duration-seconds",86400))throw new IllegalArgumentException();multiplier=mult;hourEnd=System.currentTimeMillis()+seconds*1000;getConfig().set("runtime.hour-duration",seconds*1000);saveState();Bukkit.broadcastMessage(msg("hour-start").replace("%multiplier%",String.valueOf(mult)));for(Player p:Bukkit.getOnlinePlayers())updateBar(p);}catch(Exception ex){sender.sendMessage("§cUngültiger Multiplikator oder Zeitraum.");}return true;}
            sender.sendMessage("§cUnbekannter Unterbefehl.");return true;
        }
        if(command.getName().equalsIgnoreCase("treasure")){if(!(sender instanceof Player p))return true;if(!p.hasPermission("treasurehunt.use"))return noPerm(sender);if(args.length>0&&args[0].equalsIgnoreCase("stats")){showStats(p);return true;}if(args.length>0&&args[0].equalsIgnoreCase("rewards")){openRewards(p);return true;}openTreasure(p);return true;}
        if(command.getName().equalsIgnoreCase("powerticket")){if(!(sender instanceof Player p))return true;if(!p.hasPermission("powerticket.use"))return noPerm(sender);openTicket(p);return true;}
        return true;
    }
    private long parseTime(String s){String x=s.toLowerCase(Locale.ROOT);long n=Long.parseLong(x.substring(0,x.length()-1));return switch(x.substring(x.length()-1)){case "m"->n*60;case "h"->n*3600;case "d"->n*86400;default->throw new IllegalArgumentException();};}
    private void endHour(boolean announce){hourEnd=0;multiplier=1;saveState();if(announce)broadcast("hour-end");for(Player p:Bukkit.getOnlinePlayers())updateBar(p);}
    private boolean noPerm(CommandSender s){s.sendMessage(msg("no-permission"));return true;}
    private void globalStats(CommandSender s){try(Statement st=db.createStatement();ResultSet r=st.executeQuery("SELECT COALESCE(SUM(ores),0),COALESCE(SUM(money_count),0),COALESCE(SUM(money_total),0),COALESCE(SUM(tools),0),COALESCE(SUM(spawners),0),COALESCE(SUM(tickets_found),0),COUNT(*) FROM players")){if(r.next())s.sendMessage("§6Global: §eErze §f"+r.getLong(1)+" §eGeldfunde §f"+r.getLong(2)+" §eAusgezahlt §f"+r.getLong(3)+" §eTools §f"+r.getLong(4)+" §eSpawner §f"+r.getLong(5)+" §eTickets §f"+r.getLong(6)+" §eSpieler §f"+r.getLong(7));}catch(SQLException e){s.sendMessage("§cStatistikfehler.");}}
    private long[] stats(UUID id){try(PreparedStatement ps=db.prepareStatement("SELECT ores,coal,iron,gold,diamond,emerald,redstone,lapis,copper,nether,money_count,money_total,tools,spawners,tickets_found,tickets_redeemed,daily_claims,rank_until FROM players WHERE uuid=?")){ps.setString(1,id.toString());try(ResultSet r=ps.executeQuery()){if(r.next()){long[] a=new long[18];for(int i=0;i<a.length;i++)a[i]=r.getLong(i+1);return a;}}}catch(SQLException ignored){}return new long[18];}
    private void showStats(Player p){long[] a=stats(p.getUniqueId());p.sendMessage("§6§lDEINE TREASURE-STATISTIKEN");p.sendMessage("§eErze: §f"+a[0]+" §7(Kohle "+a[1]+", Eisen "+a[2]+", Gold "+a[3]+", Diamant "+a[4]+", Smaragd "+a[5]+", Redstone "+a[6]+", Lapis "+a[7]+", Kupfer "+a[8]+", Nether "+a[9]+")");p.sendMessage("§eGeldfunde: §f"+a[10]+" §7| Summe: "+a[11]+"$ | Tools: "+a[12]+" | Spawner: "+a[13]+" | Tickets: "+a[14]+" | eingelöst: "+a[15]+" | Tagesclaims: "+a[16]);p.sendMessage("§eLetzter Fund: §f"+getString(p.getUniqueId(),"last_found","NONE")+" §7| Rang bis: "+(a[17]>0?new Date(a[17]):"—"));}
    private String getString(UUID id,String col,String def){try(Statement st=db.createStatement();ResultSet r=st.executeQuery("SELECT "+col+" FROM players WHERE uuid='"+id+"'")){return r.next()?r.getString(1):def;}catch(SQLException e){return def;}}
    private void openTreasure(Player p){Inventory inv=Bukkit.createInventory(null,27,color(getConfig().getString("gui.title","&8✦ &6TreasureHunt")));inv.setItem(10,button(Material.DIAMOND,"&bPersönliche Statistiken","&7Klicke für deine Werte"));inv.setItem(13,button(Material.CHEST,"&6Belohnungsübersicht","&7Alle möglichen Funde"));inv.setItem(16,button(Material.NETHER_STAR,"&6Power Ticket","&7Öffnet das Ticket-Menü"));inv.setItem(22,button(Material.BARRIER,"&cSchließen"));p.openInventory(inv);}
    private void openRewards(Player p){Inventory inv=Bukkit.createInventory(null,27,"§8✦ §6Belohnungen");String[] names={"Geld","PowerTool","Spawner","Power Ticket"};Material[] mats={Material.GOLD_INGOT,Material.DIAMOND_PICKAXE,Material.SPAWNER,Material.NETHER_STAR};int[] den={1000,40000,100000,150000};for(int i=0;i<4;i++)inv.setItem(10+i*2,button(mats[i],"&6"+names[i],"&7Normal: 1:"+den[i],"&7Aktuell: 1:"+Math.max(1,den[i]/multiplier)));inv.setItem(22,button(Material.ARROW,"&eZurück"));p.openInventory(inv);}
    private void openTicket(Player p){Inventory inv=Bukkit.createInventory(null,27,color(getConfig().getString("gui.ticket-title","&8✦ &6Power Ticket")));int count=ticketCount(p);long[] a=stats(p.getUniqueId());inv.setItem(11,button(Material.NETHER_STAR,"&6Power Ticket","&7Besitz: &f"+count,"&7Freischaltung: &f30 Tage Power-Rang","&7Rang bis: &f"+(a[17]>System.currentTimeMillis()?new Date(a[17]):"Nicht aktiv")));inv.setItem(13,button(Material.EMERALD,"&aTägliche Belohnungen","&7Öffnet die 30-Tage-Übersicht"));inv.setItem(15,button(Material.GOLD_INGOT,"&eTicket einlösen","&7Klicke, um die Einlösung zu bestätigen"));inv.setItem(22,button(Material.BARRIER,"&cSchließen"));p.openInventory(inv);}
    private void openDaily(Player p){Inventory inv=Bukkit.createInventory(null,54,"§8✦ §6Tagesbelohnungen");long[] a=stats(p.getUniqueId());long start=a[17]>0?a[17]-30L*86400000:a[17];int current=a[17]>0?(int)Math.min(30,Math.max(0,(System.currentTimeMillis()-(a[17]-30L*86400000))/86400000)+1):0;for(int day=1;day<=30;day++){boolean claimed=claimed(p.getUniqueId(),day),available=current>=day&&!claimed;Material mat=claimed?Material.LIME_STAINED_GLASS_PANE:available?Material.CHEST:Material.GRAY_STAINED_GLASS_PANE;List<String> lore=new ArrayList<>();lore.add(available?"&aVerfügbar – klicken zum Abholen":claimed?"&7Bereits abgeholt":"&cNoch gesperrt");for(String cmd:getDailyCommands(day))lore.add("&8"+cmd);inv.setItem(day-1,button(mat,"&6Tag "+day,lore.toArray(String[]::new)));}inv.setItem(49,button(Material.ARROW,"&eZurück"));p.openInventory(inv);}
    private List<String> getDailyCommands(int day){return getConfig().getStringList("ticket.daily-rewards."+day);}
    private boolean claimed(UUID id,int day){try(PreparedStatement ps=db.prepareStatement("SELECT 1 FROM claims WHERE uuid=? AND day=?")){ps.setString(1,id.toString());ps.setInt(2,day);try(ResultSet r=ps.executeQuery()){return r.next();}}catch(SQLException e){return false;}}
    private void claim(Player p,int day){long[] a=stats(p.getUniqueId());long end=a[17];if(end<=0||System.currentTimeMillis()>end||day<1||day>30||claimed(p.getUniqueId(),day)){p.sendMessage("§cDiese Tagesbelohnung ist nicht verfügbar.");return;}long start=end-30L*86400000;int unlocked=(int)Math.min(30,Math.max(0,(System.currentTimeMillis()-start)/86400000)+1);if(day>unlocked){p.sendMessage("§cDieser Tag ist noch gesperrt.");return;}List<String> cmds=getDailyCommands(day);if(cmds.isEmpty())cmds=getConfig().getStringList("ticket.daily-default");if(cmds.isEmpty()){p.sendMessage("§cFür diesen Tag ist keine Belohnung konfiguriert.");return;}try{db.setAutoCommit(false);try(PreparedStatement ps=db.prepareStatement("INSERT INTO claims(uuid,day) VALUES(?,?)")){ps.setString(1,p.getUniqueId().toString());ps.setInt(2,day);ps.executeUpdate();}try(PreparedStatement ps=db.prepareStatement("UPDATE players SET daily_claims=daily_claims+1 WHERE uuid=?")){ps.setString(1,p.getUniqueId().toString());ps.executeUpdate();}db.commit();}catch(SQLException ex){try{db.rollback();}catch(SQLException ignored){}p.sendMessage("§cSpeichern fehlgeschlagen; nichts wurde ausgegeben.");return;}finally{try{db.setAutoCommit(true);}catch(SQLException ignored){}}for(String cmd:cmds)Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd.replace("%player%",p.getName()));p.sendMessage("§aTagesbelohnung abgeholt!");openDaily(p);}
    private void redeemTicket(Player p){if(ticketCount(p)<1){p.sendMessage("§cDu besitzt kein Power Ticket.");return;}long[] a=stats(p.getUniqueId());if(a[17]>System.currentTimeMillis()){p.sendMessage("§cDu hast bereits einen aktiven Power-Rang.");return;}ItemStack found=null;for(ItemStack it:p.getInventory().getContents())if(isTicket(it)){found=it;break;}if(found==null)return;found.setAmount(found.getAmount()-1);String cmd=getConfig().getString("ticket.rank-command").replace("%player%",p.getName()).replace("%group%",getConfig().getString("ticket.rank-group","power"));boolean success=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);if(!success){found.setAmount(found.getAmount()+1);p.sendMessage("§cLuckPerms-Befehl fehlgeschlagen. Ticket wurde zurückgegeben.");return;}long until=System.currentTimeMillis()+30L*86400000;update(p.getUniqueId(),"tickets_redeemed=tickets_redeemed+1, ticket_redeemed=1, rank_until="+until+", ticket_start="+System.currentTimeMillis());p.sendMessage("§aPower Ticket eingelöst! 30 Tage Power-Rang freigeschaltet.");openTicket(p);}
    private ItemStack button(Material m,String name,String... lore){ItemStack it=new ItemStack(m);ItemMeta meta=it.getItemMeta();meta.setDisplayName(color(name));meta.setLore(Arrays.stream(lore).map(this::color).toList());it.setItemMeta(meta);return it;}
    @EventHandler public void onClick(InventoryClickEvent e){if(!(e.getWhoClicked() instanceof Player p))return;String title=e.getView().getTitle();if(!title.contains("TreasureHunt")&&!title.contains("Belohnungen")&&!title.contains("Power Ticket")&&!title.contains("Power Ticket bestätigen"))return;e.setCancelled(true);if(e.getRawSlot()>=e.getView().getTopInventory().getSize())return;int slot=e.getRawSlot();if(title.contains("Power Ticket bestätigen")){if(slot==11)redeemTicket(p);else if(slot==15)openTicket(p);return;}if(title.contains("Tagesbelohnungen")){if(slot>=0&&slot<30)claim(p,slot+1);else if(slot==49)openTicket(p);return;}if(title.equals("§8✦ §6Belohnungen")){if(slot==22)openTreasure(p);return;}if(title.contains("Power Ticket")){if(slot==13)openDaily(p);if(slot==15){Inventory confirm=Bukkit.createInventory(null,27,"§8Power Ticket bestätigen");confirm.setItem(11,button(Material.LIME_CONCRETE,"&aJa, Ticket einlösen"));confirm.setItem(15,button(Material.RED_CONCRETE,"&cAbbrechen"));p.openInventory(confirm);}if(slot==22)p.closeInventory();return;}if(slot==10)showStats(p);else if(slot==13)openRewards(p);else if(slot==16)openTicket(p);else if(slot==22)p.closeInventory();}
    @EventHandler public void onDrag(InventoryDragEvent e){String t=e.getView().getTitle();if(t.contains("TreasureHunt")||t.contains("Belohnungen")||t.contains("Power Ticket"))e.setCancelled(true);}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(c.getName().equalsIgnoreCase("treasurehunt")){if(args.length==1)return List.of("start","stop","reload","status","stats","hour");if(args.length==2&&args[0].equalsIgnoreCase("hour"))return List.of("2","3","stop");if(args.length==3&&args[0].equalsIgnoreCase("hour"))return List.of("30m","1h","2h","90m");}if(c.getName().equalsIgnoreCase("treasure")&&args.length==1)return List.of("stats","rewards");return Collections.emptyList();}
}
