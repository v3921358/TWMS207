/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package handling.channel;

import client.MapleCharacter;
import constants.ServerConfig;
import constants.ServerConstants;
import constants.ServerConstants.ServerType;
import constants.WorldConstants;
import handling.netty.ServerConnection;
import handling.login.LoginServer;
import handling.world.CheaterData;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import scripting.EventScriptManager;
import server.MapleSquad;
import server.MapleSquad.MapleSquadType;
import server.ServerProperties;
import server.events.*;
import server.life.PlayerNPC;
import server.maps.AramiaFireWorks;
import server.maps.MapleMapFactory;
import server.maps.MapleMapObject;
import server.stores.HiredMerchant;
import tools.ConcurrentEnumMap;
import tools.packet.CWvsContext;

public class ChannelServer {

    public static long serverStartTime;
    private final int cashRate = 1, traitRate = 10, BossDropRate = 3;// 樂豆掉寶倍率, 性向倍率, BOSS掉寶倍率(未使用)
    private short port;
    public static short DEFAULT_PORT = 7575;
    private int channel, running_MerchantID = 0;
    private String ip;
    private boolean shutdown = false, finishedShutdown = false, MegaphoneMuteState = false;
    private PlayerStorage players;
    private ServerConnection acceptor;
    private final MapleMapFactory mapFactory;
    private EventScriptManager eventSM;
    private final AramiaFireWorks works = new AramiaFireWorks();
    private static final Map<Integer, ChannelServer> instances = new HashMap<>();
    private final Map<MapleSquadType, MapleSquad> mapleSquads = new ConcurrentEnumMap<>(MapleSquadType.class);
    private final Map<Integer, HiredMerchant> merchants = new HashMap<>();
    private final List<PlayerNPC> playerNPCs = new LinkedList<>();
    private final ReentrantReadWriteLock merchLock = new ReentrantReadWriteLock(); // merchant
    private int eventmap = -1;
    private final Map<MapleEventType, MapleEvent> events = new EnumMap<>(MapleEventType.class);
    public boolean eventOn = false;
    public int eventMap = 0;
    private boolean eventWarp;
    private String eventHost;
    private String eventName;
    private boolean manualEvent = false;
    private int manualEventMap = 0;
    private boolean bomberman = false;
    private String ShopPack;
    private ChannelType channelType = ChannelType.普通;

    public enum ChannelType {

        普通(1), 混沌(2), MVP(4);
        private int type;

        private ChannelType(int type) {
            this.type = type;
        }

        public int getType() {
            return type;
        }

        public ChannelType getByType(int type) {
            for (ChannelType ct : values()) {
                if (ct.getType() == type) {
                    return ct;
                }
            }
            return 普通;
        }

        public boolean check(int type) {
            return (type & getType()) != 0;
        }
    }

    private ChannelServer(final int channel) {
        this.channel = channel;
        mapFactory = new MapleMapFactory(channel);
    }

    public static Set<Integer> getAllInstance() {
        return new HashSet<>(instances.keySet());
    }

    public final void loadEvents() {
        if (!events.isEmpty()) {
            return;
        }
        events.put(MapleEventType.CokePlay, new MapleCoconut(channel, MapleEventType.CokePlay)); // yep, coconut. same
        // shit
        events.put(MapleEventType.Coconut, new MapleCoconut(channel, MapleEventType.Coconut));
        events.put(MapleEventType.Fitness, new MapleFitness(channel, MapleEventType.Fitness));
        events.put(MapleEventType.OlaOla, new MapleOla(channel, MapleEventType.OlaOla));
        events.put(MapleEventType.OxQuiz, new MapleOxQuiz(channel, MapleEventType.OxQuiz));
        events.put(MapleEventType.Snowball, new MapleSnowball(channel, MapleEventType.Snowball));
        events.put(MapleEventType.Survival, new MapleSurvival(channel, MapleEventType.Survival));
        events.put(MapleEventType.Bingo, new MapleMultiBingo(channel, MapleEventType.Bingo));
    }

    public final void run_startup_configurations() {
        setChannel(channel); // instances.put
        try {
            eventSM = new EventScriptManager(this, ServerConfig.getEvents(true));
        } catch (Exception e) {
            System.err.println("開啟頻道加載事件腳本異常:" + e);
        }
        port = (short) (ChannelServer.DEFAULT_PORT + channel - 1);
        ip = ServerConstants.getHostAddress() + ":" + port;

        players = new PlayerStorage(channel);
        loadEvents();
        acceptor = new ServerConnection(ServerType.頻道伺服器, port, 0, channel);
        acceptor.run();
        System.out.println("頻道" + channel + " 正在監聽" + port + "端口");
        eventSM.init();
    }

    public final void shutdown() {
        if (finishedShutdown) {
            return;
        }
        broadcastPacket(CWvsContext.broadcastMsg(0, "當前頻道將進行維護。"));
        // dc all clients by hand so we get sessionClosed...
        shutdown = true;

        System.out.println("頻道 " + channel + " 正在儲存角色數據");

        getPlayerStorage().disconnectAll();

        System.out.println("頻道 " + channel + " 正在解除端口綁定");

        // temporary while we dont have !addchannel
        acceptor.close();
        instances.remove(channel);
        setFinishShutdown();
    }

    public final boolean hasFinishedShutdown() {
        return finishedShutdown;
    }

    public final MapleMapFactory getMapFactory() {
        return mapFactory;
    }

    public static final ChannelServer newInstance(final int channel) {
        return new ChannelServer(channel);
    }

    public static final ChannelServer getInstance(final int channel) {
        return instances.get(channel);
    }

    public final void addPlayer(final MapleCharacter chr) {
        getPlayerStorage().registerPlayer(chr);
    }

    public final PlayerStorage getPlayerStorage() {
        if (players == null) { // wth
            players = new PlayerStorage(channel); // wthhhh
        }
        return players;
    }

    public final void removePlayer(final MapleCharacter chr) {
        getPlayerStorage().deregisterPlayer(chr);
    }

    public final void removePlayer(final int idz, final String namez) {
        getPlayerStorage().deregisterPlayer(idz, namez);
    }

    public final String getServerMessage(int world) {
        return WorldConstants.getById(world).getScrollMessage();
    }

    public final void setServerMessage(String newMessage) {
        for (WorldConstants.Option w : WorldConstants.values()) {
            if (w.show() && w.isAvailable()) {
                w.setScrollMessage(newMessage);
            }
        }
    }

    public final void setServerMessage(int world, String newMessage) {
        WorldConstants.getById(world).setScrollMessage(newMessage);
    }

    public final int getTempFlag(int world) {
        return WorldConstants.getById(world).getFlag();
    }

    public final void broadcastPacket(final byte[] data) {
        getPlayerStorage().broadcastPacket(data);
    }

    public final void broadcastSmegaPacket(final byte[] data) {
        getPlayerStorage().broadcastSmegaPacket(data);
    }

    public final void broadcastGMPacket(final byte[] data) {
        getPlayerStorage().broadcastGMPacket(data);
    }

    public final int getExpRate(int world) {
        return WorldConstants.getById(world).getExp();
    }

    public final int getCashRate() {
        return cashRate;
    }

    public final int getChannel() {
        return channel;
    }

    public final void setChannel(final int channel) {
        instances.put(channel, this);
        LoginServer.addChannel(channel);
    }

    public static ArrayList<ChannelServer> getAllInstances() {
        return new ArrayList<>(instances.values());
    }

    public final String getIP() {
        return ip;
    }

    public final boolean isShutdown() {
        return shutdown;
    }

    public final int getLoadedMaps() {
        return mapFactory.getLoadedMaps();
    }

    public final EventScriptManager getEventSM() {
        return eventSM;
    }

    public final void reloadEvents() {
        eventSM.cancel();
        eventSM = new EventScriptManager(this, ServerConfig.getEvents(true));
        eventSM.init();
    }

    public final int getMesoRate(int world) {
        return WorldConstants.getById(world).getMeso();
    }

    public final int getDropRate(int world) {
        return WorldConstants.getById(world).getDrop();
    }

    public final int getBossDropRate() {
        return BossDropRate;
    }

    public static void startChannel_Main() {
        System.out.println("正在加載頻道...");
        serverStartTime = System.currentTimeMillis();
        int channelCount = WorldConstants.getChannelCount();
        for (int i = 1; i <= Math.min(20, channelCount > 0 ? channelCount : WorldConstants.CHANNEL_COUNT); i++) {
            newInstance(i).run_startup_configurations();
        }
        System.out.println("頻道加載完成!\r\n");
    }

    public Map<MapleSquadType, MapleSquad> getAllSquads() {
        return Collections.unmodifiableMap(mapleSquads);
    }

    public final MapleSquad getMapleSquad(final String type) {
        return getMapleSquad(MapleSquadType.valueOf(type.toLowerCase()));
    }

    public final MapleSquad getMapleSquad(final MapleSquadType type) {
        return mapleSquads.get(type);
    }

    public final boolean addMapleSquad(final MapleSquad squad, final String type) {
        final MapleSquadType types = MapleSquadType.valueOf(type.toLowerCase());
        if (types != null && !mapleSquads.containsKey(types)) {
            mapleSquads.put(types, squad);
            squad.scheduleRemoval();
            return true;
        }
        return false;
    }

    public final boolean removeMapleSquad(final MapleSquadType types) {
        if (types != null && mapleSquads.containsKey(types)) {
            mapleSquads.remove(types);
            return true;
        }
        return false;
    }

    public final int closeAllMerchant() {
        int ret = 0;
        merchLock.writeLock().lock();
        try {
            final Iterator<Entry<Integer, HiredMerchant>> merchants_ = merchants.entrySet().iterator();
            while (merchants_.hasNext()) {
                HiredMerchant hm = merchants_.next().getValue();
                hm.closeShop(true, false);
                // HiredMerchantSave.QueueShopForSave(hm);
                hm.getMap().removeMapObject(hm);
                merchants_.remove();
                ret++;
            }
        } finally {
            merchLock.writeLock().unlock();
        }
        // hacky
        for (int i = 910000001; i <= 910000022; i++) {
            for (MapleMapObject mmo : mapFactory.getMap(i).getAllHiredMerchantsThreadsafe()) {
                ((HiredMerchant) mmo).closeShop(true, false);
                // HiredMerchantSave.QueueShopForSave((HiredMerchant) mmo);
                ret++;
            }
        }
        return ret;
    }

    public final int addMerchant(final HiredMerchant hMerchant) {
        merchLock.writeLock().lock();
        try {
            running_MerchantID++;
            merchants.put(running_MerchantID, hMerchant);
            return running_MerchantID;
        } finally {
            merchLock.writeLock().unlock();
        }
    }

    public final void removeMerchant(final HiredMerchant hMerchant) {
        merchLock.writeLock().lock();

        try {
            merchants.remove(hMerchant.getStoreId());
        } finally {
            merchLock.writeLock().unlock();
        }
    }

    public final boolean containsMerchant(final int accid, int cid) {
        boolean contains = false;

        merchLock.readLock().lock();
        try {
            final Iterator itr = merchants.values().iterator();

            while (itr.hasNext()) {
                HiredMerchant hm = (HiredMerchant) itr.next();
                if (hm.getOwnerAccId() == accid || hm.getOwnerId() == cid) {
                    contains = true;
                    break;
                }
            }
        } finally {
            merchLock.readLock().unlock();
        }
        return contains;
    }

    public final List<HiredMerchant> searchMerchant(final int itemSearch) {
        final List<HiredMerchant> list = new LinkedList<>();
        merchLock.readLock().lock();
        try {
            final Iterator itr = merchants.values().iterator();

            while (itr.hasNext()) {
                HiredMerchant hm = (HiredMerchant) itr.next();
                if (hm.searchItem(itemSearch).size() > 0) {
                    list.add(hm);
                }
            }
        } finally {
            merchLock.readLock().unlock();
        }
        return list;
    }

    public HiredMerchant getHiredMerchants(int accId, int chrId) {
        merchLock.readLock().lock();
        try {
            for (HiredMerchant hm : merchants.values()) {
                if (hm.getOwnerAccId() == accId && hm.getOwnerId() == chrId) {
                    HiredMerchant localHiredMerchant = hm;
                    return localHiredMerchant;
                }
            }
        } finally {
            merchLock.readLock().unlock();
        }
        return null;
    }

    public final void toggleMegaphoneMuteState() {
        this.MegaphoneMuteState = !this.MegaphoneMuteState;
    }

    public final boolean getMegaphoneMuteState() {
        return MegaphoneMuteState;
    }

    public int getEvent() {
        return eventmap;
    }

    public final void setEvent(final int ze) {
        this.eventmap = ze;
    }

    public MapleEvent getEvent(final MapleEventType t) {
        return events.get(t);
    }

    public final Collection<PlayerNPC> getAllPlayerNPC() {
        return playerNPCs;
    }

    public final void addPlayerNPC(final PlayerNPC npc) {
        if (playerNPCs.contains(npc)) {
            return;
        }
        playerNPCs.add(npc);
        getMapFactory().getMap(npc.getMapId()).addMapObject(npc);
    }

    public final void removePlayerNPC(final PlayerNPC npc) {
        if (playerNPCs.contains(npc)) {
            playerNPCs.remove(npc);
            getMapFactory().getMap(npc.getMapId()).removeMapObject(npc);
        }
    }

    public final String getServerName() {
        return ServerConfig.SERVER_NAME;
    }

    public final int getPort() {
        return port;
    }

    public static final Set<Integer> getChannelServer() {
        return new HashSet<>(instances.keySet());
    }

    public final void setShutdown() {
        this.shutdown = true;
        System.out.println("頻道 " + channel + " 已被設置為關閉並關閉僱傭商人...");
    }

    public final void setFinishShutdown() {
        this.finishedShutdown = true;
        System.out.println("頻道 " + channel + " 關閉完成");
    }

    public final boolean isAdminOnly() {
        return ServerConfig.ADMIN_ONLY;
    }

    public static Map<Integer, Integer> getChannelLoad() {
        Map<Integer, Integer> ret = new HashMap<>();
        for (ChannelServer cs : instances.values()) {
            ret.put(cs.getChannel(), cs.getConnectedClients());
        }
        return ret;
    }

    public int getConnectedClients() {
        return getPlayerStorage().getConnectedClients();
    }

    public List<CheaterData> getCheaters() {
        List<CheaterData> cheaters = getPlayerStorage().getCheaters();

        Collections.sort(cheaters);
        return cheaters;
    }

    public List<CheaterData> getReports() {
        List<CheaterData> cheaters = getPlayerStorage().getReports();

        Collections.sort(cheaters);
        return cheaters;
    }

    public void broadcastMessage(byte[] message) {
        broadcastPacket(message);
    }

    public void broadcastSmega(byte[] message) {
        broadcastSmegaPacket(message);
    }

    public void broadcastGMMessage(byte[] message) {
        broadcastGMPacket(message);
    }

    public AramiaFireWorks getFireWorks() {
        return works;
    }

    public int getTraitRate() {
        return traitRate;
    }

    public boolean manualEvent(MapleCharacter chr) {
        if (manualEvent) {
            manualEvent = false;
            manualEventMap = 0;
        } else {
            manualEvent = true;
            manualEventMap = chr.getMapId();
        }
        if (manualEvent) {
            chr.dropMessage(5, "Manual event has " + (manualEvent ? "began" : "begone") + ".");
        }
        return manualEvent;
    }

    public void warpToEvent(MapleCharacter chr) {
        if (!manualEvent || manualEventMap <= 0) {
            chr.dropMessage(5, "There is no event being hosted.");
            return;
        }
        chr.dropMessage(5, "You are being warped into the event map.");
        chr.changeMap(manualEventMap, 0);
    }

    public boolean bombermanActive() {
        return bomberman;
    }

    public void toggleBomberman(MapleCharacter chr) {
        bomberman = !bomberman;
        if (bomberman) {
            chr.dropMessage(5, "Bomberman Event is active.");
        } else {
            chr.dropMessage(5, "Bomberman Event is not active.");
        }
    }

    public ChannelType getChannelType() {
        return channelType;
    }

    public static void loadSetting() {
        DEFAULT_PORT = ServerProperties.getProperty("CHANNEL_START_PORT", DEFAULT_PORT);
    }

    static {
        loadSetting();
    }
}
