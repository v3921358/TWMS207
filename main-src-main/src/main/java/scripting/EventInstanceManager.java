
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
package scripting;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import javax.script.ScriptException;

import client.MapleCharacter;
import client.MapleClient;
import client.MapleQuestStatus;
import client.MapleTrait.MapleTraitType;
import client.skill.SkillFactory;
import constants.GameConstants;
import handling.channel.ChannelServer;
import handling.world.MapleParty;
import handling.world.MaplePartyCharacter;
import handling.world.World;
import handling.world.exped.MapleExpedition;
import handling.world.exped.PartySearch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import server.MapleCarnivalParty;
import server.MapleItemInformationProvider;
import server.MapleSquad;
import server.Timer.EventTimer;
import server.quest.MapleQuest;
import server.life.MapleMonster;
import server.maps.MapleMap;
import server.maps.MapleMapFactory;
import tools.FileoutputUtil;
import tools.packet.CField;
import tools.Pair;
import tools.packet.CWvsContext.InfoPacket;

public class EventInstanceManager {

    private List<MapleCharacter> chars = new LinkedList<>(); // this is messy
    private List<Integer> dced = new LinkedList<>();
    private final List<Integer> charsHistory = new LinkedList<>();
    private List<MapleMonster> mobs = new LinkedList<>();
    private Map<Integer, Integer> killCount = new HashMap<>();
    private final EventManager em;
    private final int channel;
    private final String name;
    private Properties props = new Properties();
    private long timeStarted = 0;
    private long eventTime = 0;
    private List<Integer> mapIds = new LinkedList<>();
    private List<Boolean> isInstanced = new LinkedList<>();
    private ScheduledFuture<?> eventTimer;
    private final ReentrantReadWriteLock mutex = new ReentrantReadWriteLock();
    private final Lock rL = mutex.readLock(), wL = mutex.writeLock();
    private boolean disposed = false;
    private final Map<Integer, Integer> deathCounts = new TreeMap<>();
    private final List<Integer> deathCountMapIds = new LinkedList<>();
    private final Map<Integer, Map<String, Long>> aggroRankDamages = new TreeMap<>();

    public EventInstanceManager(EventManager em, String name, int channel) {
        this.em = em;
        this.name = name;
        this.channel = channel;
    }

    public void registerPlayer(MapleCharacter chr) {
        if (disposed || chr == null) {
            return;
        }
        try {
            wL.lock();
            try {
                chars.add(chr);
                charsHistory.add(chr.getId());
            } finally {
                wL.unlock();
            }
            chr.setEventInstance(this);
            em.getIv().invokeFunction("playerEntry", this, chr);
        } catch (NullPointerException ex) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, ex);
        } catch (NoSuchMethodException | ScriptException ex) {
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + em.getName() + ", Instance name : " + name
                    + ", method Name : playerEntry:\r\n" + ex);
            System.out.println("Event name" + em.getName() + ", Instance name : " + name
                    + ", method Name : playerEntry:\r\n" + ex);
        }
    }

    public void registerParty(MapleCharacter chr) {
        if (disposed || chr == null) {
            return;
        }
        MapleParty party = chr.getParty();
        if (party == null) {
            registerPlayer(chr);
        } else {
            MapleMap map = chr.getMap();
            for (MaplePartyCharacter pc : party.getMembers()) {
                registerPlayer(map.getCharacterById(pc.getId()));
            }
            PartySearch ps = World.Party.getSearch(party);
            if (ps != null) {
                World.Party.removeSearch(ps, "The Party Listing has been removed because the Party Quest started.");
            }
        }
    }

    public void changedMap(final MapleCharacter chr, final int mapid) {
        if (disposed) {
            return;
        }
        try {
            em.getIv().invokeFunction("changedMap", this, chr, mapid);
        } catch (NullPointerException npe) {
        } catch (NoSuchMethodException | ScriptException ex) {
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log,
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : changedMap:\r\n" + ex);
            System.out.println(
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : changedMap:\r\n" + ex);
        }
    }

    public void timeOut(final long delay, final EventInstanceManager eim) {
        if (disposed || eim == null) {
            return;
        }
        eventTimer = EventTimer.getInstance().schedule(new Runnable() {
            @Override
            public void run() {
                if (disposed || eim == null || em == null) {
                    return;
                }
                try {
                    em.getIv().invokeFunction("scheduledTimeout", eim);
                } catch (NoSuchMethodException | ScriptException ex) {
                    FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + em.getName() + ", Instance name : "
                            + name + ", method Name : scheduledTimeout:\r\n" + ex);
                    System.out.println("Event name" + em.getName() + ", Instance name : " + name
                            + ", method Name : scheduledTimeout:\r\n" + ex);
                }
            }
        }, delay);
    }

    public void stopEventTimer() {
        eventTime = 0;
        timeStarted = 0;
        if (eventTimer != null) {
            eventTimer.cancel(false);
        }
    }

    public void restartEventTimer(long time) {
        try {
            if (disposed) {
                return;
            }
            timeStarted = System.currentTimeMillis();
            eventTime = time;
            if (eventTimer != null) {
                eventTimer.cancel(false);
            }
            eventTimer = null;
            final int timesend = (int) time / 1000;

            for (MapleCharacter chr : getPlayers()) {
                if (name.startsWith("PVP")) {
                    chr.getClient().getSession()
                            .writeAndFlush(CField.getPVPClock(Integer.parseInt(getProperty("type")), timesend));
                } else {
                    chr.getClient().getSession().writeAndFlush(CField.getClock(timesend));
                }
            }
            timeOut(time, this);

        } catch (NumberFormatException ex) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, ex);
            System.out.println("Event name" + em.getName() + ", Instance name : " + name
                    + ", method Name : restartEventTimer:\r\n");
        }
    }

    public void startEventTimer(long time) {
        restartEventTimer(time); // just incase
    }

    public boolean isTimerStarted() {
        return eventTime > 0 && timeStarted > 0;
    }

    public long getTimeLeft() {
        return eventTime - (System.currentTimeMillis() - timeStarted);
    }

    public void registerParty(MapleParty party, MapleMap map) {
        if (disposed) {
            return;
        }
        for (MaplePartyCharacter pc : party.getMembers()) {
            registerPlayer(map.getCharacterById(pc.getId()));
        }
        PartySearch ps = World.Party.getSearch(party);
        if (ps != null) {
            World.Party.removeSearch(ps, "The Party Listing has been removed because the Party Quest started.");
        }
    }

    public void registerExpedition(MapleClient c, MapleExpedition exped, MapleMap map) {
        if (disposed) {
            return;
        }
        for (MapleCharacter pc : exped.getExpeditionMembers(c)) {
            registerPlayer(pc);
        }
        PartySearch ps = World.Party.getSearch(exped);
        if (ps != null) {
            World.Party.removeSearch(ps, "The Party Listing has been removed because the Party Quest started.");
        }
    }

    public void unregisterPlayerAzwan(MapleCharacter chr) {
        wL.lock();
        try {
            chars.remove(chr);
        } finally {
            wL.unlock();
        }
        chr.setEventInstance(null);
    }

    public void unregisterAll() {
        wL.lock();
        try {
            for (MapleCharacter chr : chars) {
                chr.setEventInstance(null);
            }
            chars.clear();
        } finally {
            wL.unlock();
        }
    }

    public void unregisterPlayer(final MapleCharacter chr) {
        if (disposed) {
            chr.setEventInstance(null);
            return;
        }
        wL.lock();
        try {
            unregisterPlayer_NoLock(chr);
        } finally {
            wL.unlock();
        }
    }

    private boolean unregisterPlayer_NoLock(final MapleCharacter chr) {
        if (name.equals("CWKPQ")) { // hard code it because i said so
            final MapleSquad squad = ChannelServer.getInstance(channel).getMapleSquad("CWKPQ");// so fkin hacky
            if (squad != null) {
                squad.removeMember(chr.getName());
                if (squad.getLeaderName().equals(chr.getName())) {
                    em.setProperty("leader", "false");
                }
            }
        }
        chr.setEventInstance(null);
        if (disposed) {
            return false;
        }
        if (chars.contains(chr)) {
            chars.remove(chr);
            return true;
        }
        return false;
    }

    public final boolean disposeIfPlayerBelow(final byte size, final int towarp) {
        if (disposed) {
            return true;
        }
        MapleMap map = null;
        if (towarp > 0) {
            map = this.getMapFactory().getMap(towarp);
        }

        wL.lock();
        try {
            if (chars != null && chars.size() <= size) {
                final List<MapleCharacter> chrs = new LinkedList<>(chars);
                for (MapleCharacter chr : chrs) {
                    if (chr == null) {
                        continue;
                    }
                    unregisterPlayer_NoLock(chr);
                    if (map != null) {
                        chr.changeMap(map, map.getPortal(0));
                    }
                }
                dispose_NoLock();
                return true;
            }
        } catch (Exception ex) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, ex);
        } finally {
            wL.unlock();
        }
        return false;
    }

    public final void saveBossQuest(final int points) {
        if (disposed) {
            return;
        }
        for (MapleCharacter chr : getPlayers()) {
            final MapleQuestStatus record = chr.getQuestNAdd(MapleQuest.getInstance(GameConstants.BOSS_PQ));

            if (record.getCustomData() != null) {
                record.setCustomData(String.valueOf(points + Integer.parseInt(record.getCustomData())));
            } else {
                record.setCustomData(String.valueOf(points)); // First time
            }
            chr.modifyCSPoints(1, points / 5, true);
            chr.getTrait(MapleTraitType.will).addExp(points / 100, chr);
        }
    }

    public final void saveNX(final int points) {
        if (disposed) {
            return;
        }
        for (MapleCharacter chr : getPlayers()) {
            chr.modifyCSPoints(1, points, true);
        }
    }

    public List<MapleCharacter> getPlayers() {
        if (disposed) {
            return Collections.emptyList();
        }
        rL.lock();
        try {
            return new LinkedList<>(chars);
        } finally {
            rL.unlock();
        }
    }

    public List<Integer> getDisconnected() {
        return dced;
    }

    public final int getPlayerCount() {
        if (disposed) {
            return 0;
        }
        return chars.size();
    }

    public void registerMonster(MapleMonster mob) {
        if (disposed) {
            return;
        }
        mobs.add(mob);
        mob.setEventInstance(this);
    }

    public void unregisterMonster(MapleMonster mob) {
        mob.setEventInstance(null);
        if (disposed) {
            return;
        }
        if (mobs.contains(mob)) {
            mobs.remove(mob);
        }
        if (mobs.isEmpty()) {
            try {
                em.getIv().invokeFunction("allMonstersDead", this);
            } catch (NoSuchMethodException | ScriptException ex) {
                FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + em.getName() + ", Instance name : "
                        + name + ", method Name : allMonstersDead:\n" + ex);
                System.out.println("Event name" + em.getName() + ", Instance name : " + name
                        + ", method Name : allMonstersDead:\n" + ex);
            }
        }
    }

    public void playerKilled(MapleCharacter chr) {
        if (disposed) {
            return;
        }
        try {
            em.getIv().invokeFunction("playerDead", this, chr);
        } catch (NoSuchMethodException | ScriptException ex) {
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log,
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : playerDead:\n" + ex);
            System.out.println(
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : playerDead:\n" + ex);
        }
    }

    public boolean revivePlayer(MapleCharacter chr) {
        if (disposed) {
            return false;
        }
        try {
            Object b = em.getIv().invokeFunction("playerRevive", this, chr);
            if (b instanceof Boolean) {
                return (Boolean) b;
            }
        } catch (NoSuchMethodException | ScriptException ex) {
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log,
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : playerRevive:\n" + ex);
            System.out.println(
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : playerRevive:\n" + ex);
        }
        return true;
    }

    public void playerDisconnected(final MapleCharacter chr, int idz) {
        if (disposed) {
            return;
        }
        byte ret;
        try {
            ret = ((Integer) em.getIv().invokeFunction("playerDisconnected", this, chr)).byteValue();
        } catch (NoSuchMethodException | ScriptException e) {
            ret = 0;
        }

        wL.lock();
        try {
            if (disposed) {
                return;
            }
            if (chr == null || chr.isAlive()) {
                dced.add(idz);
            }
            if (chr != null) {
                unregisterPlayer_NoLock(chr);
            }
            if (ret == 0) {
                if (getPlayerCount() <= 0) {
                    dispose_NoLock();
                }
            } else if ((ret > 0 && getPlayerCount() < ret)
                    || (ret < 0 && (isLeader(chr) || getPlayerCount() < (ret * -1)))) {
                final List<MapleCharacter> chrs = new LinkedList<>(chars);
                for (MapleCharacter player : chrs) {
                    if (player.getId() != idz) {
                        removePlayer(player);
                    }
                }
                dispose_NoLock();
            }
        } catch (Exception ex) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, ex);
        } finally {
            wL.unlock();
        }

    }

    /**
     *
     * @param chr
     * @param mob
     */
    public void monsterKilled(final MapleCharacter chr, final MapleMonster mob) {
        if (disposed) {
            return;
        }
        try {
            Object ob = em.getIv().invokeFunction("monsterValue", this, mob.getId());
            int inc;
            if (ob instanceof Double) {
                inc = ((Double) ob).intValue();
            } else if (ob instanceof Float) {
                inc = ((Float) ob).intValue();
            } else if (ob instanceof Long) {
                inc = ((Long) ob).intValue();
            } else if (ob instanceof Integer) {
                inc = (Integer) ob;
            } else if (ob instanceof Short) {
                inc = (Short) ob;
            } else {
                inc = ((Byte) ob).intValue();
            }
            if (disposed || chr == null) {
                return;
            }
            Integer kc = killCount.get(chr.getId());
            if (kc == null) {
                kc = inc;
            } else {
                kc += inc;
            }
            killCount.put(chr.getId(), kc);
            if (chr.getCarnivalParty() != null && (mob.getStats().getPoint() > 0 || mob.getStats().getCP() > 0)) {
                em.getIv().invokeFunction("monsterKilled", this, chr,
                        mob.getStats().getCP() > 0 ? mob.getStats().getCP() : mob.getStats().getPoint());
            }
        } catch (ScriptException ex) {
            System.out.println("Event name" + (em == null ? "null" : em.getName()) + ", Instance name : " + name
                    + ", method Name : monsterValue:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + (em == null ? "null" : em.getName())
                    + ", Instance name : " + name + ", method Name : monsterValue:\r\n" + ex);
        } catch (NoSuchMethodException ex) {
            System.out.println("Event name" + (em == null ? "null" : em.getName()) + ", Instance name : " + name
                    + ", method Name : monsterValue:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + (em == null ? "null" : em.getName())
                    + ", Instance name : " + name + ", method Name : monsterValue:\r\n" + ex);
        } catch (Exception ex) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, ex);
        }
    }

    public void monsterDamaged(final MapleCharacter chr, final MapleMonster mob, final int damage) {
        if (disposed) {
            return;
        }
        try {
            em.getIv().invokeFunction("monsterDamaged", this, chr, mob.getId(), damage);
        } catch (ScriptException | NoSuchMethodException ex) {
            System.out.println("Event name" + (em == null ? "null" : em.getName()) + ", Instance name : " + name
                    + ", method Name : monsterValue:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + (em == null ? "null" : em.getName())
                    + ", Instance name : " + name + ", method Name : monsterValue:\r\n" + ex);
        } catch (Exception ex) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, ex);
        }
    }

    public void addPVPScore(final MapleCharacter chr, final int score) {
        if (disposed) { // ghost PQ boss only.
            return;
        }
        try {
            em.getIv().invokeFunction("addPVPScore", this, chr, score);
        } catch (ScriptException | NoSuchMethodException ex) {
            System.out.println("Event name" + (em == null ? "null" : em.getName()) + ", Instance name : " + name
                    + ", method Name : monsterValue:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + (em == null ? "null" : em.getName())
                    + ", Instance name : " + name + ", method Name : monsterValue:\r\n" + ex);
        } catch (Exception ex) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, ex);
        }
    }

    public int getKillCount(MapleCharacter chr) {
        if (disposed) {
            return 0;
        }
        Integer kc = killCount.get(chr.getId());
        if (kc == null) {
            return 0;
        } else {
            return kc;
        }
    }

    public void dispose_NoLock() {
        if (disposed || em == null) {
            return;
        }
        final String emN = em.getName();
        try {

            disposed = true;
            for (MapleCharacter chr : chars) {
                chr.setEventInstance(null);
            }
            chars.clear();
            chars = null;
            if (mobs.size() >= 1) {
                for (MapleMonster mob : mobs) {
                    if (mob != null) {
                        mob.setEventInstance(null);
                    }
                }
            }
            mobs.clear();
            mobs = null;
            killCount.clear();
            killCount = null;
            dced.clear();
            dced = null;
            timeStarted = 0;
            eventTime = 0;
            props.clear();
            props = null;
            for (int i = 0; i < mapIds.size(); i++) {
                if (isInstanced.get(i)) {
                    this.getMapFactory().removeInstanceMap(mapIds.get(i));
                }
            }
            mapIds.clear();
            mapIds = null;
            isInstanced.clear();
            isInstanced = null;
            deathCounts.clear();
            deathCountMapIds.clear();
            aggroRankDamages.clear();
            charsHistory.clear();
            em.disposeInstance(name);
        } catch (Exception e) {
            System.out.println("Caused by : " + emN + " instance name: " + name + " method: dispose:");
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, e);
        }
    }

    public void dispose() {
        wL.lock();
        try {
            dispose_NoLock();
        } finally {
            wL.unlock();
        }

    }

    public ChannelServer getChannelServer() {
        return ChannelServer.getInstance(channel);
    }

    public List<MapleMonster> getMobs() {
        return mobs;
    }

    public final void broadcastPlayerMsg(final int type, final String msg) {
        if (disposed) {
            return;
        }
        for (MapleCharacter chr : getPlayers()) {
            chr.dropMessage(type, msg);
        }
    }

    // PVP
    public final List<Pair<Integer, String>> newPair() {
        return new ArrayList<>();
    }

    public void addToPair(List<Pair<Integer, String>> e, int e1, String e2) {
        e.add(new Pair<>(e1, e2));
    }

    public final List<Pair<Integer, MapleCharacter>> newPair_chr() {
        return new ArrayList<>();
    }

    public void addToPair_chr(List<Pair<Integer, MapleCharacter>> e, int e1, MapleCharacter e2) {
        e.add(new Pair<>(e1, e2));
    }

    public final void broadcastPacket(byte[] p) {
        if (disposed) {
            return;
        }
        for (MapleCharacter chr : getPlayers()) {
            chr.getClient().getSession().writeAndFlush(p);
        }
    }

    public final void broadcastTeamPacket(byte[] p, int team) {
        if (disposed) {
            return;
        }
        for (MapleCharacter chr : getPlayers()) {
            if (chr.getTeam() == team) {
                chr.getClient().getSession().writeAndFlush(p);
            }
        }
    }

    public final MapleMap createInstanceMap(final int mapid) {
        if (disposed) {
            return null;
        }
        final int assignedid = EventScriptManager.getNewInstanceMapId();
        mapIds.add(assignedid);
        isInstanced.add(true);
        return this.getMapFactory().CreateInstanceMap(mapid, true, true, true, assignedid);
    }

    public final MapleMap createInstanceMapS(final int mapid) {
        if (disposed) {
            return null;
        }
        final int assignedid = EventScriptManager.getNewInstanceMapId();
        mapIds.add(assignedid);
        isInstanced.add(true);
        return this.getMapFactory().CreateInstanceMap(mapid, false, false, false, assignedid);
    }

    public final MapleMap setInstanceMap(final int mapid) { // gets instance map from the channelserv
        if (disposed) {
            return this.getMapFactory().getMap(mapid);
        }
        mapIds.add(mapid);
        isInstanced.add(false);
        return this.getMapFactory().getMap(mapid);
    }

    public final MapleMapFactory getMapFactory() {
        return getChannelServer().getMapFactory();
    }

    public final MapleMap getMapInstance(int args) {
        if (disposed) {
            return null;
        }
        try {
            boolean instanced = false;
            int trueMapID = -1;
            if (args >= mapIds.size()) {
                // assume real map
                trueMapID = args;
            } else {
                trueMapID = mapIds.get(args);
                instanced = isInstanced.get(args);
            }
            MapleMap map = null;
            if (!instanced) {
                map = this.getMapFactory().getMap(trueMapID);
                if (map == null) {
                    return null;
                }
                // in case reactors need shuffling and we are actually loading the map
                if (map.getCharactersSize() == 0) {
                    if (em.getProperty("shuffleReactors") != null && em.getProperty("shuffleReactors").equals("true")) {
                        map.shuffleReactors();
                    }
                }
            } else {
                map = this.getMapFactory().getInstanceMap(trueMapID);
                if (map == null) {
                    return null;
                }
                // in case reactors need shuffling and we are actually loading the map
                if (map.getCharactersSize() == 0) {
                    if (em.getProperty("shuffleReactors") != null && em.getProperty("shuffleReactors").equals("true")) {
                        map.shuffleReactors();
                    }
                }
            }
            return map;
        } catch (NullPointerException ex) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, ex);
            return null;
        }
    }

    public final void schedule(final String methodName, final long delay) {
        if (disposed) {
            return;
        }
        EventTimer.getInstance().schedule(new Runnable() {
            @Override
            public void run() {
                if (disposed || EventInstanceManager.this == null || em == null) {
                    return;
                }
                try {
                    em.getIv().invokeFunction(methodName, EventInstanceManager.this);
                } catch (NullPointerException npe) {
                } catch (NoSuchMethodException | ScriptException ex) {
                    System.out.println("Event name" + em.getName() + ", Instance name : " + name + ", method Name : "
                            + methodName + ":\n" + ex);
                    FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + em.getName() + ", Instance name : "
                            + name + ", method Name(schedule) : " + methodName + " :\n" + ex);
                }
            }
        }, delay);
    }

    public final String getName() {
        return name;
    }

    public final void setProperty(final String key, final String value) {
        if (disposed) {
            return;
        }
        props.setProperty(key, value);
    }

    public final Object setProperty(final String key, final String value, final boolean prev) {
        if (disposed) {
            return null;
        }
        return props.setProperty(key, value);
    }

    public final String getProperty(final String key) {
        if (disposed) {
            return "";
        }
        return props.getProperty(key);
    }

    public final Properties getProperties() {
        return props;
    }

    public final void leftParty(final MapleCharacter chr) {
        if (disposed) {
            return;
        }
        try {
            em.getIv().invokeFunction("leftParty", this, chr);
        } catch (NoSuchMethodException | ScriptException ex) {
            System.out.println(
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : leftParty:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log,
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : leftParty:\r\n" + ex);
        }
    }

    public final void disbandParty() {
        if (disposed) {
            return;
        }
        try {
            em.getIv().invokeFunction("disbandParty", this);
        } catch (NoSuchMethodException | ScriptException ex) {
            System.out.println("Event name" + em.getName() + ", Instance name : " + name
                    + ", method Name : disbandParty:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + em.getName() + ", Instance name : " + name
                    + ", method Name : disbandParty:\r\n" + ex);
        }
    }

    // Separate function to warp players to a "finish" map, if applicable
    public final void finishPQ() {
        if (disposed) {
            return;
        }
        try {
            em.getIv().invokeFunction("clearPQ", this);
        } catch (NoSuchMethodException | ScriptException ex) {
            System.out.println(
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : clearPQ:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log,
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : clearPQ:\r\n" + ex);
        }
    }

    public final void removePlayer(final MapleCharacter chr) {
        if (disposed) {
            return;
        }
        try {
            em.getIv().invokeFunction("playerExit", this, chr);
        } catch (NoSuchMethodException | ScriptException ex) {
            System.out.println(
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : playerExit:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log,
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : playerExit:\r\n" + ex);
        }
    }

    public final void registerCarnivalParty(final MapleCharacter leader, final MapleMap map, final byte team) {
        if (disposed) {
            return;
        }
        leader.clearCarnivalRequests();
        List<MapleCharacter> characters = new LinkedList<>();
        final MapleParty party = leader.getParty();

        if (party == null) {
            return;
        }
        for (MaplePartyCharacter pc : party.getMembers()) {
            final MapleCharacter c = map.getCharacterById(pc.getId());
            if (c != null) {
                characters.add(c);
                registerPlayer(c);
                c.resetCP();
            }
        }
        PartySearch ps = World.Party.getSearch(party);
        if (ps != null) {
            World.Party.removeSearch(ps, "The Party Listing has been removed because the Party Quest started.");
        }
        final MapleCarnivalParty carnivalParty = new MapleCarnivalParty(leader, characters, team);
        try {
            em.getIv().invokeFunction("registerCarnivalParty", this, carnivalParty);
        } catch (ScriptException ex) {
            System.out.println("Event name" + em.getName() + ", Instance name : " + name
                    + ", method Name : registerCarnivalParty:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Event name" + em.getName() + ", Instance name : " + name
                    + ", method Name : registerCarnivalParty:\r\n" + ex);
        } catch (NoSuchMethodException ex) {
            // ignore
        }
    }

    public void onMapLoad(final MapleCharacter chr) {
        if (disposed) {
            return;
        }
        try {
            em.getIv().invokeFunction("onMapLoad", this, chr);
        } catch (ScriptException ex) {
            System.out.println(
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : onMapLoad:\r\n" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log,
                    "Event name" + em.getName() + ", Instance name : " + name + ", method Name : onMapLoad:\r\n" + ex);
        } catch (NoSuchMethodException ex) {
            // Ignore, we don't want to update this for all events.
        }
    }

    public boolean isLeader(final MapleCharacter chr) {
        return (chr != null && chr.getParty() != null && chr.getParty().getLeader().getId() == chr.getId());
    }

    public void registerSquad(MapleSquad squad, MapleMap map, int questID) {
        if (disposed) {
            return;
        }
        final int mapid = map.getId();

        for (String chr : squad.getMembers()) {
            MapleCharacter player = squad.getChar(chr);
            if (player != null && player.getMapId() == mapid) {
                if (questID > 0) {
                    player.getQuestNAdd(MapleQuest.getInstance(questID))
                            .setCustomData(String.valueOf(System.currentTimeMillis()));
                }
                registerPlayer(player);
                if (player.getParty() != null) {
                    PartySearch ps = World.Party.getSearch(player.getParty());
                    if (ps != null) {
                        World.Party.removeSearch(ps,
                                "The Party Listing has been removed because the Party Quest has started.");
                    }
                }
            }
        }
        squad.setStatus((byte) 2);
        squad.getBeginMap().broadcastMessage(CField.stopClock());
    }

    public boolean isDisconnected(final MapleCharacter chr) {
        if (disposed) {
            return false;
        }
        return (dced.contains(chr.getId()));
    }

    public void removeDisconnected(final int id) {
        if (disposed) {
            return;
        }
        dced.remove(id);
    }

    public EventManager getEventManager() {
        return em;
    }

    public void applyBuff(final MapleCharacter chr, final int id) {
        MapleItemInformationProvider.getInstance().getItemEffect(id).applyTo(chr);
        chr.getClient().getSession().writeAndFlush(InfoPacket.getStatusMsg(id));
    }

    public void applySkill(final MapleCharacter chr, final int id) {
        SkillFactory.getSkill(id).getEffect(1).applyTo(chr);
    }

    public int getDeathCount(int cid) {
        return deathCounts != null && deathCounts.containsKey(cid) ? deathCounts.get(cid) : -1;
    }

    public void setAllDeathCount(int deathCount) {
        for (MapleCharacter chr : chars) {
            setDeathCount(chr.getId(), deathCount);
        }
    }

    public void setDeathCount(int cid, int deathCount) {
        deathCounts.put(cid, deathCount);
        if (deathCount == -1) {
            return;
        }
        for (MapleCharacter chr : chars) {
            if (cid != chr.getId()) {
                continue;
            }
            for (int mapId : deathCountMapIds) {
                if (chr.getMapId() == mapId) {
                    chr.getClient().getSession().writeAndFlush(CField.getDeathCountInfo(false, deathCount));
                    break;
                }
            }
        }
    }

    public void addShowDeathCountMap(int mapId) {
        deathCountMapIds.add(mapId);
        for (MapleCharacter chr : chars) {
            if (chr.getMapId() == mapId && getDeathCount(chr.getId()) != -1) {
                chr.getClient().getSession().writeAndFlush(CField.getDeathCountInfo(false, getDeathCount(chr.getId())));
            }
        }
    }

    public boolean isShowDeathCountMap(int mapId) {
        for (int map : deathCountMapIds) {
            if (map == mapId) {
                return true;
            }
        }
        return false;
    }

    public void addAggroRankMobDamage(int mapId, int mobId, String name, int damage) {
        Map<String, Long> aggroRankMobDamages = null;
        if (aggroRankDamages.containsKey(mapId)) {
            aggroRankMobDamages = aggroRankDamages.get(mapId);
        }
        if (aggroRankMobDamages == null) {
            aggroRankMobDamages = new TreeMap<>();
        }
        aggroRankMobDamages.put(name,
                (aggroRankMobDamages.containsKey(name) ? aggroRankMobDamages.get(name) : 0) + damage);
        aggroRankDamages.put(mapId, aggroRankMobDamages);

        for (MapleCharacter c : chars) {
            if (c.getMapId() != mapId) {
                continue;
            }
            c.getClient().getSession().writeAndFlush(CField.getAggroRankInfo(getAggroRank(mapId)));
        }
    }

    public List<String> getAggroRank(int mapId) {
        Map<String, Long> aggroRankMobDamages = null;
        if (aggroRankDamages.containsKey(mapId)) {
            aggroRankMobDamages = aggroRankDamages.get(mapId);
        }
        if (aggroRankMobDamages == null) {
            aggroRankMobDamages = new TreeMap<>();
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(aggroRankMobDamages.entrySet());
        Collections.sort(list,
                (Map.Entry<String, Long> o1, Map.Entry<String, Long> o2) -> o1.getValue().compareTo(o2.getValue()));
        List<String> rankNames = new ArrayList<>();
        for (Map.Entry<String, Long> mapping : list) {
            rankNames.add(mapping.getKey());
        }
        return rankNames;
    }

    public void addPQLog(String pqName, int count) {
        List<Integer> charList = new LinkedList<>(charsHistory);
        for (MapleCharacter chr : chars) {
            charList.remove((Integer) chr.getId());
            chr.setPQLog(pqName, 0, count);
        }
        MapleCharacter chr;
        for (int cid : charList) {
            chr = MapleCharacter.getCharacterById(cid);
            if (chr != null) {
                chr.setPQLog(pqName, 0, count);
            }
        }
    }
}
