package server.commands;

import client.MapleCharacter;
import client.MapleClient;
import client.skill.SkillFactory;
import client.inventory.Item;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import client.inventory.MaplePet;
import constants.GameConstants;
import constants.ServerConfig;
import tools.LoadPacket;
import database.ManagerDatabasePool;
import handling.MapleServerHandler;
import handling.cashshop.CashShopServer;
import handling.channel.ChannelServer;
import handling.world.MapleMessengerCharacter;
import handling.world.World;
import java.awt.Color;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import scripting.NPCScriptManager;
import server.CashItemFactory;
import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import server.MaplePortal;
import server.Randomizer;
import server.ShutdownServer;
import server.Timer.EventTimer;
import server.Timer.WorldTimer;
import server.life.MapleMonster;
import tools.StringUtil;
import tools.packet.CField;
import tools.packet.CField.CScriptMan;
import tools.packet.CWvsContext;
import tools.packet.PetPacket;

public class AdminCommand {

    public static PlayerGMRank getPlayerLevelRequired() {
        return PlayerGMRank.ADMIN;
    }

    public static class UpdatePet extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MaplePet pet = c.getPlayer().getSummonedPet(0);
            if (pet == null) {
                return 0;
            }
            c.getPlayer().getMap().broadcastMessage(c.getPlayer(), PetPacket.petColor(c.getPlayer().getId(), (byte) 0, Color.yellow.getAlpha()), true);
            return 1;
        }
    }

    public static class DamageBuff extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            SkillFactory.getSkill(9101003).getEffect(1).applyTo(c.getPlayer());
            return 1;
        }
    }

    public static class MagicWheel extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            List<Integer> items = new LinkedList<>();
            for (int i = 1; i <= 10; i++) {
                try {
                    items.add(Integer.parseInt(splitted[i]));
                } catch (NumberFormatException ex) {
                    items.add(GameConstants.eventRareReward[GameConstants.eventRareReward.length]);
                }
            }
            int end = Randomizer.nextInt(10);
            String data = "Magic Wheel";
            c.getPlayer().setWheelItem(items.get(end));
            c.getSession().writeAndFlush(CWvsContext.magicWheel((byte) 3, items, data, end));
            return 1;
        }
    }

    public static class UnsealItem extends CommandExecute {

        @Override
        public int execute(final MapleClient c, String[] splitted) {
            short slot = Short.parseShort(splitted[1]);
            Item item = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slot);
            if (item == null) {
                return 0;
            }
            final int itemId = item.getItemId();
            Integer[] itemArray = {1002140, 1302000, 1302001, 1302002, 1302003, 1302004, 1302005, 1302006, 1302007};
            final List<Integer> items = Arrays.asList(itemArray);
            c.getSession().writeAndFlush(CField.sendSealedBox(slot, 2028162, items)); // sealed box
            final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
            WorldTimer.getInstance().schedule(new Runnable() {
                @Override
                public void run() {
                    MapleInventoryManipulator.removeById(c, GameConstants.getInventoryType(itemId), itemId, 1, false,
                            false);
                    Item item = ii.getEquipById(items.get(Randomizer.nextInt(items.size())));
                    MapleInventoryManipulator.addbyItem(c, item);
                    c.getSession().writeAndFlush(CField.EffectPacket.unsealBox(item.getItemId()));
                    c.getSession().writeAndFlush(CField.EffectPacket.showRewardItemAnimation(2028162, "")); // sealed
                    // box
                }
            }, 10000);
            return 1;
        }
    }

    public static class DemonJob extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            c.getSession().writeAndFlush(CScriptMan.getJobSelection(0, 1));
            return 1;
        }
    }

    public static class testPacket extends 測試檔案數據包 {

    }

    public static class 測試檔案數據包 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            c.getSession().writeAndFlush(LoadPacket.getPacket());
            return 1;
        }
    }

    public static class ClosestPortal extends 最近傳送點 {

    }

    public static class 最近傳送點 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MaplePortal portal = c.getPlayer().getMap().findClosestPortal(c.getPlayer().getTruePosition());
            c.getPlayer().dropMessage(-11,
                    portal.getName() + " id: " + portal.getId() + " script: " + portal.getScriptName());
            return 1;
        }
    }

    public static class Uptime extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            c.getPlayer().dropMessage(-11, "Server has been up for "
                    + StringUtil.getReadableMillis(ChannelServer.serverStartTime, System.currentTimeMillis()));
            return 1;
        }
    }

    public static class Reward extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MapleCharacter chr = c.getChannelServer().getPlayerStorage().getCharacterByName(splitted[1]);
            chr.addReward(!splitted[2].equals("null"), Integer.parseInt(splitted[3]), Integer.parseInt(splitted[4]),
                    Integer.parseInt(splitted[5]), Integer.parseInt(splitted[6]), Integer.parseInt(splitted[7]),
                    StringUtil.joinStringFrom(splitted, 8));
            chr.updateReward();
            return 1;
        }
    }

    public static class DropMessage extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            String type = splitted[1];
            String text = splitted[2];
            if (type == null) {
                c.getPlayer().dropMessage(-11, "Syntax error: !dropmessage type text");
                return 0;
            }
            if (type.length() > 1) {
                c.getPlayer().dropMessage(-11, "Type must be just with one word");
                return 0;
            }
            if (text == null && text.length() < 1) {
                c.getPlayer().dropMessage(-11, "Text must be 1 letter or more!!");
                return 0;
            }
            c.getPlayer().dropMessage(Integer.parseInt(type), text);
            return 1;
        }
    }

    public static class DropMsg extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            String type = splitted[1];
            String text = splitted[2];
            if (type == null) {
                c.getPlayer().dropMessage(-11, "Syntax error: !dropmessage type text");
                return 0;
            }
            if (type.length() > 1) {
                c.getPlayer().dropMessage(-11, "Type must be just with one word");
                return 0;
            }
            if (text == null && text.length() < 1) {
                c.getPlayer().dropMessage(-11, "Text must be 1 letter or more!!");
                return 0;
            }
            // c.getPlayer().dropMsg(Integer.parseInt(type), text);
            return 1;
        }
    }

    public static class setGM extends 設置管理員 {

    }

    public static class 設置管理員 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            c.getChannelServer().getPlayerStorage().getCharacterByName(splitted[1])
                    .setGmLevel(PlayerGMRank.getByLevel(Byte.parseByte(splitted[2])));
            return 1;
        }
    }

    public static class warpCS extends 傳送到商城 {

    }

    public static class 傳送到商城 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length < 2) {
                c.getPlayer().dropMessage(-11, splitted[0] + " <玩家名稱>");
                return 0;
            }
            MapleCharacter chr = c.getChannelServer().getPlayerStorage().getCharacterByName(splitted[1]);
            MapleClient client = chr.getClient();
            final ChannelServer ch = ChannelServer.getInstance(client.getChannel());

            chr.changeRemoval();

            if (chr.getMessenger() != null) {
                MapleMessengerCharacter messengerplayer = new MapleMessengerCharacter(chr);
                World.Messenger.leaveMessenger(chr.getMessenger().getId(), messengerplayer);
            }
            World.ChannelChange_Data(c, chr, MapleServerHandler.CASH_SHOP_SERVER);
            ch.removePlayer(chr);
            client.updateLoginState(MapleClient.CHANGE_CHANNEL, client.getSessionIPAddress());
            chr.saveToDB(false, false);
            chr.getMap().removePlayer(chr);
            client.getSession().writeAndFlush(
                    CField.getChannelChange(Integer.parseInt(CashShopServer.getIP().split(":")[1])));
            client.setPlayer(null);
            client.setReceiving(false);
            return 1;
        }
    }

    public static class Autoreg extends 自動註冊 {

    }

    public static class 自動註冊 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            ServerConfig.AUTO_REGISTER = !ServerConfig.AUTO_REGISTER;
            c.getPlayer().dropMessage(-11, "自動註冊狀態: " + (ServerConfig.AUTO_REGISTER ? "開啟" : "關閉"));
            System.out.println("自動註冊狀態: " + (ServerConfig.AUTO_REGISTER ? "開啟" : "關閉"));
            return 1;
        }
    }

    public static class StripEveryone extends 脫掉線上 {

    }

    public static class 脫掉線上 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            ChannelServer cs = c.getChannelServer();
            for (MapleCharacter mchr : cs.getPlayerStorage().getAllCharacters()) {
                if (c.getPlayer().isIntern()) {
                    continue;
                }
                MapleInventory equipped = mchr.getInventory(MapleInventoryType.EQUIPPED);
                MapleInventory equip = mchr.getInventory(MapleInventoryType.EQUIP);
                List<Short> ids = new ArrayList<>();
                for (Item item : equipped.newList()) {
                    ids.add(item.getPosition());
                }
                for (short id : ids) {
                    MapleInventoryManipulator.unequip(mchr.getClient(), id, equip.getNextFreeSlot());
                }
            }
            return 1;
        }
    }

    public static class Strip extends 脫掉 {

    }

    public static class 脫掉 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length < 2) {
                c.getPlayer().dropMessage(-11, splitted[0] + " <玩家名稱> (空 - 不發送公告/其他 - 發送世界公告:默認空)");
                return 0;
            }
            MapleCharacter victim = c.getChannelServer().getPlayerStorage().getCharacterByName(splitted[1]);
            MapleInventory equipped = victim.getInventory(MapleInventoryType.EQUIPPED);
            MapleInventory equip = victim.getInventory(MapleInventoryType.EQUIP);
            List<Short> ids = new ArrayList<>();
            for (Item item : equipped.newList()) {
                ids.add(item.getPosition());
            }
            for (short id : ids) {
                MapleInventoryManipulator.unequip(victim.getClient(), id, equip.getNextFreeSlot());
            }
            boolean notice = false;
            if (splitted.length > 1) {
                notice = true;
            }
            if (notice) {
                World.Broadcast.broadcastMessage(CWvsContext.broadcastMsg(0,
                        victim.getName() + " has been stripped by " + c.getPlayer().getName()));
            }
            return 1;
        }
    }

    public static class giveAllMeso extends 給所有人楓幣 {

    }

    public static class 給所有人楓幣 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                for (MapleCharacter mch : cserv.getPlayerStorage().getAllCharacters()) {
                    mch.gainMeso(Long.parseLong(splitted[1]), true);
                }
            }
            return 1;
        }
    }

    public static class 關鍵時刻 extends CommandExecute {

        protected static ScheduledFuture<?> ts = null;

        @Override
        public int execute(final MapleClient c, String[] splitted) {
            if (splitted.length < 2) {
                c.getPlayer().dropMessage(-11, splitted[0] + " <時間:分鐘>");
                return 0;
            }
            if (ts != null) {
                ts.cancel(false);
                c.getPlayer().dropMessage(-11, "原定的關鍵時刻已取消。");
            }
            int minutesLeft = Integer.parseInt(splitted[1]);
            ts = EventTimer.getInstance().schedule(new Runnable() {
                @Override
                public void run() {
                    for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                        for (MapleCharacter mch : cserv.getPlayerStorage().getAllCharacters()) {
                            if (c.canClickNPC()) {
                                NPCScriptManager.getInstance().start(mch.getClient(), 9010010, "關鍵時刻");
                            }
                        }
                    }
                    ts.cancel(false);
                    ts = null;
                }
            }, minutesLeft * 60000);
            c.getPlayer().dropMessage(-11, "關鍵時刻預定已完成。");
            return 1;
        }
    }

    public static class warpallhere extends 線上來這裡 {

    }

    public static class 線上來這裡 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            for (MapleCharacter mch : c.getChannelServer().getPlayerStorage().getAllCharacters()) {
                if (mch.getMapId() != c.getPlayer().getMapId()) {
                    mch.changeMap(c.getPlayer().getMap(), c.getPlayer().getPosition());
                }
            }
            return 1;
        }
    }

    public static class dcall extends 線上下線 {

    }

    public static class 線上下線 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            int range = -1;
            switch (splitted[1]) {
                case "m":
                    range = 0;
                    break;
                case "c":
                    range = 1;
                    break;
                case "w":
                    range = 2;
                    break;
            }
            if (range == -1) {
                range = 1;
            }
            switch (range) {
                case 0:
                    c.getPlayer().getMap().disconnectAll();
                    break;
                case 1:
                    c.getChannelServer().getPlayerStorage().disconnectAll(true);
                    break;
                case 2:
                    for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                        cserv.getPlayerStorage().disconnectAll(true);
                    }
                    break;
                default:
                    break;
            }
            return 1;
        }
    }

    public static class shutdown extends 關閉伺服器 {

    }

    public static class 關閉伺服器 extends CommandExecute {

        protected static Thread t = null;

        @Override
        public int execute(MapleClient c, String[] splitted) {
            c.getPlayer().dropMessage(-11, "正在關閉伺服器...");
            if (t == null || !t.isAlive()) {
                t = new Thread(ShutdownServer.getInstance());
                ShutdownServer.getInstance().shutdown();
                t.start();
            } else {
                c.getPlayer().dropMessage(-11, "關閉進程正在進行或者關閉已完成，請稍候。");
            }
            return 1;
        }
    }

    public static class shutdowntime extends 定時關閉伺服器 {

    }

    public static class 定時關閉伺服器 extends 關閉伺服器 {

        private static ScheduledFuture<?> ts = null;
        private int minutesLeft = 0;

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length < 2) {
                c.getPlayer().dropMessage(-11, splitted[0] + " <時間:分鐘>");
                return 0;
            }
            minutesLeft = Integer.parseInt(splitted[1]);
            c.getPlayer().dropMessage(-11, "伺服器將在" + minutesLeft + " 分鐘后關閉");
            if (ts == null && (t == null || !t.isAlive())) {
                t = new Thread(ShutdownServer.getInstance());
                ts = EventTimer.getInstance().register(new Runnable() {
                    @Override
                    public void run() {
                        if (minutesLeft == 0) {
                            ShutdownServer.getInstance().shutdown();
                            t.start();
                            ts.cancel(false);
                            return;
                        }
                        World.Broadcast.broadcastMessage(CWvsContext.broadcastMsg(0,
                                "伺服器將在" + minutesLeft + " 分鐘后進行停機維護, 請及時安全的下線, 以免造成不必要的損失。"));
                        minutesLeft--;
                    }
                }, 60000);
            } else {
                c.getPlayer().dropMessage(-11, "關閉進程正在進行或者關閉已完成，請稍候。");
            }
            return 1;
        }
    }

    public static class sql extends 數據庫 {

    }

    public static class 數據庫 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length < 2) {
                c.getPlayer().dropMessage(-11, splitted[0] + " <SQL命令>");
                return 0;
            }
            try {
                Connection con = ManagerDatabasePool.getConnection();
                try (PreparedStatement ps = (PreparedStatement) con
                        .prepareStatement(StringUtil.joinStringFrom(splitted, 1))) {
                    ps.executeUpdate();
                }
                ManagerDatabasePool.closeConnection(con);
            } catch (SQLException e) {
                c.getPlayer().dropMessage(-11, "執行SQL命令失敗");
                return 0;
            }
            return 1;
        }
    }

    public static class reloadCS extends 重載商城數據 {

    }

    public static class 重載商城數據 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            CashItemFactory.getInstance().initialize(true);
            c.getPlayer().dropMessage(-11, "重載商城數據完成。");
            return 1;
        }
    }

    public static class spawnElite extends 召喚菁英王 {

    }

    public static class 召喚菁英王 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            c.getPlayer().getMap().spawnEliteBoss((MapleMonster) c.getPlayer().getMap()
                    .getMonstersInRange(c.getPlayer().getPosition(), Double.POSITIVE_INFINITY).get(0));
            return 1;
        }
    }
}
