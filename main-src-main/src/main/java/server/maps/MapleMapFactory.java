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
package server.maps;

import constants.GameConstants;
import database.ManagerDatabasePool;
import provider.MapleData;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import server.MaplePortal;
import server.Randomizer;
import server.life.AbstractLoadedMapleLife;
import server.life.MapleLifeFactory;
import server.life.MapleMonster;
import server.life.MapleNPC;
import server.maps.MapleNodes.DirectionInfo;
import server.maps.MapleNodes.MapleNodeInfo;
import server.maps.MapleNodes.MaplePlatform;
import tools.FileoutputUtil;
import tools.Pair;
import tools.StringUtil;

import java.awt.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class MapleMapFactory {

    private final MapleDataProvider[] source = new MapleDataProvider[]{
        MapleDataProviderFactory.getDataProvider("Map"), MapleDataProviderFactory.getDataProvider("Map2"),};
    private final MapleData nameData = MapleDataProviderFactory.getDataProvider("String").getData("Map.img");
    private final HashMap<Integer, MapleMap> maps = new HashMap<>();
    private final HashMap<Integer, MapleMap> instanceMap = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private static final Map<Integer, List<AbstractLoadedMapleLife>> customLife = new HashMap<>();
    private int channel;

    public static int loadCustomLife(boolean reload) {
        if (reload) {
            customLife.clear(); // init
        }
        if (!customLife.isEmpty()) {
            return customLife.size();
        }
        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (java.sql.PreparedStatement ps = con.prepareStatement("SELECT * FROM `wz_customlife`");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final int mapid = rs.getInt("mid");
                    final AbstractLoadedMapleLife myLife = loadLife(rs.getInt("dataid"), rs.getInt("f"),
                            rs.getByte("hide") > 0, rs.getInt("fh"), rs.getInt("cy"), rs.getInt("rx0"),
                            rs.getInt("rx1"), rs.getInt("x"), rs.getInt("y"), rs.getString("type"),
                            rs.getInt("mobtime"));
                    if (myLife == null) {
                        continue;
                    }
                    final List<AbstractLoadedMapleLife> entries = customLife.get(mapid);
                    final List<AbstractLoadedMapleLife> collections = new ArrayList<>();
                    if (entries == null) {
                        collections.add(myLife);
                        customLife.put(mapid, collections);
                    } else {
                        collections.addAll(entries); // re-add
                        collections.add(myLife);
                        customLife.put(mapid, collections);
                    }
                }
            }
            loadCustomNPC();
            ManagerDatabasePool.closeConnection(con);
            return customLife.size();
            // System.out.println("Successfully loaded " + customLife.size() + " maps with
            // custom life.");
        } catch (SQLException e) {
            System.out.println("Error loading custom life..." + e);
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, e);
        }
        return -1;
    }

    public static void loadCustomNPC() {
        List<Pair<Integer, AbstractLoadedMapleLife>> customNPC = new LinkedList<>();
        // customNPC.add(new Pair<>(910000000, loadLife(9010038, 0, false, 78, 34, 312,
        // 312, 312, 34, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(9010037, 0, false, 46, -266,
        // 932, 932, 932, -266, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(9000019, 0, true, 47, -266, 650,
        // 650, 650, -266, "n", 0)));//cuz RPS no works..
        // customNPC.add(new Pair<>(910000000, loadLife(9000031, 0, false, 108, -326,
        // 72, 170, 120, -329, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(9010029, 0, false, 107, -326,
        // -729, -729, -729, -329, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(1033225, 0, true, 42, -266, 638,
        // 638, 638, -266, "n", 0)));//Shadow Knight cuz not coded..
        // customNPC.add(new Pair<>(910000000, loadLife(9072000, 0, false, 40, -266,
        // 1098, 1098, 1098, -266, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(9010034, 0, false, 59, 4, 749,
        // 749, 749, 4, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(9010035, 0, false, 61, 4, 1116,
        // 1116, 1116, 4, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(9010036, 0, false, 62, 4, 935,
        // 935, 935, 4, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(9201117, 0, false, 45, -266,
        // 1092, 1092, 1092, -266, "n", 0)));
        // customNPC.add(new Pair<>(910000000, loadLife(9900002, 0, false, 57, 4, 603,
        // 603, 603, 4, "n", 0)));
        // id, face, hide, fh, y, x, x, x, y, "n", 1000
        for (int i = 0; i < customNPC.size(); i++) {
            final int mapid = customNPC.get(i).getLeft();
            final AbstractLoadedMapleLife myLife = customNPC.get(i).getRight();
            if (myLife == null) {
                continue;
            }
            final List<AbstractLoadedMapleLife> entries = customLife.get(mapid);
            final List<AbstractLoadedMapleLife> collections = new ArrayList<>();
            if (entries == null) {
                collections.add(myLife);
                customLife.put(mapid, collections);
            } else {
                collections.addAll(entries); // re-add
                collections.add(myLife);
                customLife.put(mapid, collections);
            }
        }
    }

    public MapleMapFactory(int channel) {
        this.channel = channel;
    }

    public final MapleMap getMap(final int mapid) {
        return getMap(mapid, true, true, true);
    }

    private static AbstractLoadedMapleLife loadLife(int id, int f, boolean hide, int fh, int cy, int rx0, int rx1,
            int x, int y, String type, int mtime) {
        final AbstractLoadedMapleLife myLife = MapleLifeFactory.getLife(id, type);
        if (myLife == null) {
            System.out.println("Custom npc " + id + " is null...");
            return null;
        }
        myLife.setCy(cy);
        myLife.setF(f);
        myLife.setFh(fh);
        myLife.setRx0(rx0);
        myLife.setRx1(rx1);
        myLife.setPosition(new Point(x, y));
        myLife.setHide(hide);
        myLife.setMTime(mtime);
        myLife.setCType(type);
        return myLife;
    }

    // backwards-compatible
    public final MapleMap getMap(final int mapid, final boolean respawns, final boolean npcs) {
        return getMap(mapid, respawns, npcs, true);
    }

    public final MapleMap getMap(final int mapid, final boolean respawns, final boolean npcs, final boolean reactors) {
        Integer omapid = mapid;
        MapleMap map = maps.get(omapid);
        if (map == null) {
            lock.lock();
            try {
                map = maps.get(omapid);
                if (map != null) {
                    return map;
                }
                MapleData mapData = null;
                MapleDataProvider src = null;
                for (MapleDataProvider dat : source) {
                    try {
                        src = dat;
                        if (src == null) {
                            break;
                        }
                        mapData = src.getData(getMapName(mapid));
                    } catch (Exception e) {
                        return null;
                    }
                    if (mapData != null) {
                        break;
                    }
                }
                if (mapData == null || src == null) {
                    return null;
                }
                MapleData link = mapData.getChildByPath("info/link");
                if (link != null) {
                    mapData = src.getData(getMapName(MapleDataTool.getIntConvert("info/link", mapData)));
                }

                float monsterRate = 0;
                if (respawns) {
                    MapleData mobRate = mapData.getChildByPath("info/mobRate");
                    if (mobRate != null) {
                        monsterRate = ((Float) mobRate.getData());
                    }
                }
                map = new MapleMap(mapid, channel, MapleDataTool.getInt("info/returnMap", mapData), monsterRate);

                loadPortals(map, mapData.getChildByPath("portal"));
                map.setTop(MapleDataTool.getInt(mapData.getChildByPath("info/VRTop"), 0));
                map.setLeft(MapleDataTool.getInt(mapData.getChildByPath("info/VRLeft"), 0));
                map.setBottom(MapleDataTool.getInt(mapData.getChildByPath("info/VRBottom"), 0));
                map.setRight(MapleDataTool.getInt(mapData.getChildByPath("info/VRRight"), 0));
                List<MapleFoothold> allFootholds = new LinkedList<>();
                Point lBound = new Point();
                Point uBound = new Point();
                MapleFoothold fh;

                for (MapleData footRoot : mapData.getChildByPath("foothold").getChildren()) {
                    for (MapleData footCat : footRoot.getChildren()) {
                        for (MapleData footHold : footCat.getChildren()) {
                            fh = new MapleFoothold(
                                    new Point(MapleDataTool.getInt(footHold.getChildByPath("x1"), 0),
                                            MapleDataTool.getInt(footHold.getChildByPath("y1"), 0)),
                                    new Point(MapleDataTool.getInt(footHold.getChildByPath("x2"), 0),
                                            MapleDataTool.getInt(footHold.getChildByPath("y2"), 0)),
                                    Integer.parseInt(footHold.getName()));
                            fh.setPrev((short) MapleDataTool.getInt(footHold.getChildByPath("prev"), 0));
                            fh.setNext((short) MapleDataTool.getInt(footHold.getChildByPath("next"), 0));

                            if (fh.getX1() < lBound.x) {
                                lBound.x = fh.getX1();
                            }
                            if (fh.getX2() > uBound.x) {
                                uBound.x = fh.getX2();
                            }
                            if (fh.getY1() < lBound.y) {
                                lBound.y = fh.getY1();
                            }
                            if (fh.getY2() > uBound.y) {
                                uBound.y = fh.getY2();
                            }
                            allFootholds.add(fh);
                        }
                    }
                }
                MapleFootholdTree fTree = new MapleFootholdTree(lBound, uBound);
                for (MapleFoothold foothold : allFootholds) {
                    fTree.insert(foothold);
                }
                map.setFootholds(fTree);
                if (map.getTop() == 0) {
                    map.setTop(lBound.y);
                }
                if (map.getBottom() == 0) {
                    map.setBottom(uBound.y);
                }
                if (map.getLeft() == 0) {
                    map.setLeft(lBound.x);
                }
                if (map.getRight() == 0) {
                    map.setRight(uBound.x);
                }
                int bossid = -1;
                String msg = null;
                if (mapData.getChildByPath("info/timeMob") != null) {
                    bossid = MapleDataTool.getInt(mapData.getChildByPath("info/timeMob/id"), 0);
                    msg = MapleDataTool.getString(mapData.getChildByPath("info/timeMob/message"), null);
                }

                // load life data (npc, monsters)
                List<Point> herbRocks = new ArrayList<>();
                int lowestLevel = 200, highestLevel = 0;
                String type, limited;
                AbstractLoadedMapleLife myLife;

                for (MapleData life : mapData.getChildByPath("life").getChildren()) {
                    type = MapleDataTool.getString(life.getChildByPath("type"));
                    limited = MapleDataTool.getString("limitedname", life, "");
                    if ((npcs || !type.equals("n")) && !limited.equals("Stage0")) { // alien pq stuff
                        myLife = loadLife(life, MapleDataTool.getString(life.getChildByPath("id")), type);

                        if (myLife instanceof MapleMonster && !GameConstants.isNoSpawn(mapid)) {
                            final MapleMonster mob = (MapleMonster) myLife;

                            herbRocks.add(map.addMonsterSpawn(mob, MapleDataTool.getInt("mobTime", life, 0),
                                    (byte) MapleDataTool.getInt("team", life, -1), mob.getId() == bossid ? msg : null)
                                    .getPosition());
                            if (mob.getStats().getLevel() > highestLevel && !mob.getStats().isBoss()) {
                                highestLevel = mob.getStats().getLevel();
                            }
                            if (mob.getStats().getLevel() < lowestLevel && !mob.getStats().isBoss()) {
                                lowestLevel = mob.getStats().getLevel();
                            }
                        } else if (myLife instanceof MapleNPC) {
                            boolean spawn = true;
                            for (int id : GameConstants.unusedNpcs) {
                                if (myLife.getId() == id) {
                                    spawn = false;
                                }
                            }
                            if (spawn) {
                                map.addMapObject(myLife);
                            }
                        }
                    }
                    final List<AbstractLoadedMapleLife> custom = customLife.get(mapid);
                    if (custom != null) {
                        for (AbstractLoadedMapleLife n : custom) {
                            switch (n.getCType()) {
                                case "n":
                                    map.addMapObject(n);
                                    break;
                                case "m":
                                    final MapleMonster monster = (MapleMonster) n;
                                    map.addMonsterSpawn(monster, n.getMTime(), (byte) -1, null);
                                    break;
                            }
                        }
                    }

                }
                addAreaBossSpawn(map);
                map.setCreateMobInterval(
                        (short) MapleDataTool.getInt(mapData.getChildByPath("info/createMobInterval"), 9000));
                map.setFixedMob(MapleDataTool.getInt(mapData.getChildByPath("info/fixedMobCapacity"), 0));
                map.setPartyBonusRate(GameConstants.getPartyPlay(mapid,
                        MapleDataTool.getInt(mapData.getChildByPath("info/partyBonusR"), 0)));
                map.loadMonsterRate(true);
                map.setNodes(loadNodes(mapid, mapData));

                // load reactor data
                String id;
                if (reactors && mapData.getChildByPath("reactor") != null) {
                    for (MapleData reactor : mapData.getChildByPath("reactor").getChildren()) {
                        id = MapleDataTool.getString(reactor.getChildByPath("id"));
                        if (id != null) {
                            map.spawnReactor(loadReactor(reactor, id,
                                    (byte) MapleDataTool.getInt(reactor.getChildByPath("f"), 0)));
                        }
                    }
                }
                map.setFirstUserEnter(MapleDataTool.getString(mapData.getChildByPath("info/onFirstUserEnter"), ""));
                map.setUserEnter(mapid == GameConstants.JAIL ? "jail"
                        : MapleDataTool.getString(mapData.getChildByPath("info/onUserEnter"), ""));
                if (reactors && herbRocks.size() > 0 && highestLevel >= 30 && map.getFirstUserEnter().equals("")
                        && map.getUserEnter().equals("")) {
                    final List<Integer> allowedSpawn = new ArrayList<>(24);
                    allowedSpawn.add(100011);
                    allowedSpawn.add(200011);
                    if (highestLevel >= 100) {
                        for (int i = 0; i < 10; i++) {
                            for (int x = 0; x < 4; x++) { // to make heartstones rare
                                allowedSpawn.add(100000 + i);
                                allowedSpawn.add(200000 + i);
                            }
                        }
                    } else {
                        for (int i = (lowestLevel % 10 > highestLevel % 10 ? 0 : (lowestLevel % 10)); i < (highestLevel
                                % 10); i++) {
                            for (int x = 0; x < 4; x++) { // to make heartstones rare
                                allowedSpawn.add(100000 + i);
                                allowedSpawn.add(200000 + i);
                            }
                        }
                    }
                    final int numSpawn = Randomizer.nextInt(allowedSpawn.size()) / 6; // 0-7
                    for (int i = 0; i < numSpawn && !herbRocks.isEmpty(); i++) {
                        final int idd = allowedSpawn.get(Randomizer.nextInt(allowedSpawn.size()));
                        final int theSpawn = Randomizer.nextInt(herbRocks.size());
                        final MapleReactor myReactor = new MapleReactor(MapleReactorFactory.getReactor(idd), idd);
                        myReactor.setPosition(herbRocks.get(theSpawn));
                        myReactor.setDelay(idd % 100 == 11 ? 60000 : 5000); // in the reactor's wz
                        map.spawnReactor(myReactor);
                        herbRocks.remove(theSpawn);
                    }
                }

                try {
                    map.setMapName(
                            MapleDataTool.getString("mapName", nameData.getChildByPath(getMapStringName(omapid)), ""));
                    map.setStreetName(MapleDataTool.getString("streetName",
                            nameData.getChildByPath(getMapStringName(omapid)), ""));
                } catch (Exception e) {
                    map.setMapName("");
                    map.setStreetName("");
                }
                map.setClock(mapData.getChildByPath("clock") != null); // clock was changed in wz to have
                // x,y,width,height
                map.setEverlast(MapleDataTool.getInt(mapData.getChildByPath("info/everlast"), 0) > 0);
                map.setTown(MapleDataTool.getInt(mapData.getChildByPath("info/town"), 0) > 0);
                map.setSoaring(MapleDataTool.getInt(mapData.getChildByPath("info/needSkillForFly"), 0) > 0);
                map.setPersonalShop(MapleDataTool.getInt(mapData.getChildByPath("info/personalShop"), 0) > 0);
                map.setForceMove(MapleDataTool.getInt(mapData.getChildByPath("info/lvForceMove"), 0));
                map.setHPDec(MapleDataTool.getInt(mapData.getChildByPath("info/decHP"), 0));
                map.setHPDecInterval(MapleDataTool.getInt(mapData.getChildByPath("info/decHPInterval"), 10000));
                map.setHPDecProtect(MapleDataTool.getInt(mapData.getChildByPath("info/protectItem"), 0));
                map.setForcedReturnMap(mapid == 0 ? 999999999
                        : MapleDataTool.getInt(mapData.getChildByPath("info/forcedReturn"), 999999999));
                map.setTimeLimit(MapleDataTool.getInt(mapData.getChildByPath("info/timeLimit"), -1));
                map.setFieldLimit(MapleDataTool.getInt(mapData.getChildByPath("info/fieldLimit"), 0));
                map.setRecoveryRate(MapleDataTool.getFloat(mapData.getChildByPath("info/recovery"), 1));
                map.setFixedMob(MapleDataTool.getInt(mapData.getChildByPath("info/fixedMobCapacity"), 0));
                map.setPartyBonusRate(GameConstants.getPartyPlay(mapid,
                        MapleDataTool.getInt(mapData.getChildByPath("info/partyBonusR"), 0)));
                map.setConsumeItemCoolTime(MapleDataTool.getInt(mapData.getChildByPath("info/consumeItemCoolTime"), 0));

                maps.put(omapid, map);
            } finally {
                lock.unlock();
            }
        }
        return map;
    }

    public MapleMap getInstanceMap(final int instanceid) {
        return instanceMap.get(instanceid);
    }

    public void removeInstanceMap(final int instanceid) {
        lock.lock();
        try {
            if (isInstanceMapLoaded(instanceid)) {
                getInstanceMap(instanceid).checkStates("");
                instanceMap.remove(instanceid);
            }
        } finally {
            lock.unlock();
        }
    }

    public void removeMap(final int instanceid) {
        lock.lock();
        try {
            if (isMapLoaded(instanceid)) {
                getMap(instanceid).checkStates("");
                maps.remove(instanceid);
            }
        } finally {
            lock.unlock();
        }
    }

    public MapleMap CreateInstanceMap(int mapid, boolean respawns, boolean npcs, boolean reactors, int instanceid) {
        lock.lock();
        try {
            if (isInstanceMapLoaded(instanceid)) {
                return getInstanceMap(instanceid);
            }
        } finally {
            lock.unlock();
        }
        MapleData mapData = null;
        MapleDataProvider src = null;
        for (MapleDataProvider dat : source) {
            try {
                src = dat;
                if (src == null) {
                    break;
                }
                mapData = src.getData(getMapName(mapid));
            } catch (Exception e) {
                return null;
            }
            if (mapData != null) {
                break;
            }
        }
        if (mapData == null || src == null) {
            return null;
        }
        MapleData link = mapData.getChildByPath("info/link");
        if (link != null) {
            mapData = src.getData(getMapName(MapleDataTool.getIntConvert("info/link", mapData)));
        }

        float monsterRate = 0;
        if (respawns) {
            MapleData mobRate = mapData.getChildByPath("info/mobRate");
            if (mobRate != null) {
                monsterRate = ((Float) mobRate.getData());
            }
        }
        MapleMap map = new MapleMap(mapid, channel, MapleDataTool.getInt("info/returnMap", mapData), monsterRate);
        loadPortals(map, mapData.getChildByPath("portal"));
        map.setTop(MapleDataTool.getInt(mapData.getChildByPath("info/VRTop"), 0));
        map.setLeft(MapleDataTool.getInt(mapData.getChildByPath("info/VRLeft"), 0));
        map.setBottom(MapleDataTool.getInt(mapData.getChildByPath("info/VRBottom"), 0));
        map.setRight(MapleDataTool.getInt(mapData.getChildByPath("info/VRRight"), 0));
        List<MapleFoothold> allFootholds = new LinkedList<>();
        Point lBound = new Point();
        Point uBound = new Point();
        for (MapleData footRoot : mapData.getChildByPath("foothold").getChildren()) {
            for (MapleData footCat : footRoot.getChildren()) {
                for (MapleData footHold : footCat.getChildren()) {
                    MapleFoothold fh = new MapleFoothold(
                            new Point(MapleDataTool.getInt(footHold.getChildByPath("x1")),
                                    MapleDataTool.getInt(footHold.getChildByPath("y1"))),
                            new Point(MapleDataTool.getInt(footHold.getChildByPath("x2")),
                                    MapleDataTool.getInt(footHold.getChildByPath("y2"))),
                            Integer.parseInt(footHold.getName()));
                    fh.setPrev((short) MapleDataTool.getInt(footHold.getChildByPath("prev")));
                    fh.setNext((short) MapleDataTool.getInt(footHold.getChildByPath("next")));

                    if (fh.getX1() < lBound.x) {
                        lBound.x = fh.getX1();
                    }
                    if (fh.getX2() > uBound.x) {
                        uBound.x = fh.getX2();
                    }
                    if (fh.getY1() < lBound.y) {
                        lBound.y = fh.getY1();
                    }
                    if (fh.getY2() > uBound.y) {
                        uBound.y = fh.getY2();
                    }
                    allFootholds.add(fh);
                }
            }
        }
        MapleFootholdTree fTree = new MapleFootholdTree(lBound, uBound);
        for (MapleFoothold fh : allFootholds) {
            fTree.insert(fh);
        }
        map.setFootholds(fTree);
        if (map.getTop() == 0) {
            map.setTop(lBound.y);
        }
        if (map.getBottom() == 0) {
            map.setBottom(uBound.y);
        }
        if (map.getLeft() == 0) {
            map.setLeft(lBound.x);
        }
        if (map.getRight() == 0) {
            map.setRight(uBound.x);
        }
        int bossid = -1;
        String msg = null;
        if (mapData.getChildByPath("info/timeMob") != null) {
            bossid = MapleDataTool.getInt(mapData.getChildByPath("info/timeMob/id"), 0);
            msg = MapleDataTool.getString(mapData.getChildByPath("info/timeMob/message"), null);
        }

        // load life data (npc, monsters)
        String type, limited;
        AbstractLoadedMapleLife myLife;

        for (MapleData life : mapData.getChildByPath("life").getChildren()) {
            type = MapleDataTool.getString(life.getChildByPath("type"));
            limited = MapleDataTool.getString("limitedname", life, "");
            if ((npcs || !type.equals("n")) && limited.equals("")) {
                myLife = loadLife(life, MapleDataTool.getString(life.getChildByPath("id")), type);

                if (myLife instanceof MapleMonster && !GameConstants.isNoSpawn(mapid)) {
                    final MapleMonster mob = (MapleMonster) myLife;

                    map.addMonsterSpawn(mob, MapleDataTool.getInt("mobTime", life, 0),
                            (byte) MapleDataTool.getInt("team", life, -1), mob.getId() == bossid ? msg : null);

                } else if (myLife instanceof MapleNPC) {
                    map.addMapObject(myLife);
                }
            }
        }
        addAreaBossSpawn(map);
        map.setCreateMobInterval((short) MapleDataTool.getInt(mapData.getChildByPath("info/createMobInterval"), 9000));
        map.setFixedMob(MapleDataTool.getInt(mapData.getChildByPath("info/fixedMobCapacity"), 0));
        map.setPartyBonusRate(
                GameConstants.getPartyPlay(mapid, MapleDataTool.getInt(mapData.getChildByPath("info/partyBonusR"), 0)));
        map.loadMonsterRate(true);
        map.setNodes(loadNodes(mapid, mapData));

        // load reactor data
        String id;
        if (reactors && mapData.getChildByPath("reactor") != null) {
            for (MapleData reactor : mapData.getChildByPath("reactor").getChildren()) {
                id = MapleDataTool.getString(reactor.getChildByPath("id"));
                if (id != null) {
                    map.spawnReactor(
                            loadReactor(reactor, id, (byte) MapleDataTool.getInt(reactor.getChildByPath("f"), 0)));
                }
            }
        }
        try {
            map.setMapName(MapleDataTool.getString("mapName", nameData.getChildByPath(getMapStringName(mapid)), ""));
            map.setStreetName(
                    MapleDataTool.getString("streetName", nameData.getChildByPath(getMapStringName(mapid)), ""));
        } catch (Exception e) {
            map.setMapName("");
            map.setStreetName("");
        }
        map.setClock(MapleDataTool.getInt(mapData.getChildByPath("info/clock"), 0) > 0);
        map.setEverlast(MapleDataTool.getInt(mapData.getChildByPath("info/everlast"), 0) > 0);
        map.setTown(MapleDataTool.getInt(mapData.getChildByPath("info/town"), 0) > 0);
        map.setSoaring(MapleDataTool.getInt(mapData.getChildByPath("info/needSkillForFly"), 0) > 0);
        map.setForceMove(MapleDataTool.getInt(mapData.getChildByPath("info/lvForceMove"), 0));
        map.setHPDec(MapleDataTool.getInt(mapData.getChildByPath("info/decHP"), 0));
        map.setHPDecInterval(MapleDataTool.getInt(mapData.getChildByPath("info/decHPInterval"), 10000));
        map.setHPDecProtect(MapleDataTool.getInt(mapData.getChildByPath("info/protectItem"), 0));
        map.setForcedReturnMap(MapleDataTool.getInt(mapData.getChildByPath("info/forcedReturn"), 999999999));
        map.setTimeLimit(MapleDataTool.getInt(mapData.getChildByPath("info/timeLimit"), -1));
        map.setFieldLimit(MapleDataTool.getInt(mapData.getChildByPath("info/fieldLimit"), 0));
        map.setFirstUserEnter(MapleDataTool.getString(mapData.getChildByPath("info/onFirstUserEnter"), ""));
        map.setUserEnter(MapleDataTool.getString(mapData.getChildByPath("info/onUserEnter"), ""));
        map.setRecoveryRate(MapleDataTool.getFloat(mapData.getChildByPath("info/recovery"), 1));
        map.setConsumeItemCoolTime(MapleDataTool.getInt(mapData.getChildByPath("info/consumeItemCoolTime"), 0));
        map.setInstanceId(instanceid);
        lock.lock();
        try {
            instanceMap.put(instanceid, map);
        } finally {
            lock.unlock();
        }
        return map;
    }

    public int getLoadedMaps() {
        return maps.size();
    }

    public boolean isMapLoaded(int mapId) {
        return maps.containsKey(mapId);
    }

    public boolean isInstanceMapLoaded(int instanceid) {
        return instanceMap.containsKey(instanceid);
    }

    public void clearLoadedMap() {
        lock.lock();
        try {
            maps.clear();
        } finally {
            lock.unlock();
        }
    }

    public List<MapleMap> getAllLoadedMaps() {
        List<MapleMap> ret = new ArrayList<>();
        lock.lock();
        try {
            ret.addAll(maps.values());
            ret.addAll(instanceMap.values());
        } finally {
            lock.unlock();
        }
        return ret;
    }

    public Collection<MapleMap> getAllMaps() {
        return maps.values();
    }

    private AbstractLoadedMapleLife loadLife(MapleData life, String id, String type) {
        AbstractLoadedMapleLife myLife = MapleLifeFactory.getLife(Integer.parseInt(id), type);
        if (myLife == null) {
            return null;
        }
        myLife.setCy(MapleDataTool.getInt(life.getChildByPath("cy")));
        MapleData dF = life.getChildByPath("f");
        if (dF != null) {
            myLife.setF(MapleDataTool.getInt(dF));
        }
        myLife.setFh(MapleDataTool.getInt(life.getChildByPath("fh")));
        myLife.setRx0(MapleDataTool.getInt(life.getChildByPath("rx0")));
        myLife.setRx1(MapleDataTool.getInt(life.getChildByPath("rx1")));
        myLife.setPosition(new Point(MapleDataTool.getInt(life.getChildByPath("x")),
                MapleDataTool.getInt(life.getChildByPath("y"))));

        if (MapleDataTool.getInt("hide", life, 0) == 1 && myLife instanceof MapleNPC) {
            myLife.setHide(true);
            // } else if (hide > 1) {
            // System.err.println("Hide > 1 ("+ hide +")");
        }
        return myLife;
    }

    private MapleReactor loadReactor(final MapleData reactor, final String id, final byte FacingDirection) {
        final MapleReactor myReactor = new MapleReactor(MapleReactorFactory.getReactor(Integer.parseInt(id)),
                Integer.parseInt(id));

        myReactor.setFacingDirection(FacingDirection);
        myReactor.setPosition(new Point(MapleDataTool.getInt(reactor.getChildByPath("x")),
                MapleDataTool.getInt(reactor.getChildByPath("y"))));
        myReactor.setDelay(MapleDataTool.getInt(reactor.getChildByPath("reactorTime")) * 1000);
        myReactor.setName(MapleDataTool.getString(reactor.getChildByPath("name"), ""));

        return myReactor;
    }

    private String getMapName(int mapid) {
        String mapName = StringUtil.getLeftPaddedStr(Integer.toString(mapid), '0', 9);
        StringBuilder builder = new StringBuilder("Map/Map");
        builder.append(mapid / 100000000);
        builder.append("/");
        builder.append(mapName);
        builder.append(".img");

        mapName = builder.toString();
        return mapName;
    }

    private String getMapStringName(int mapid) {
        StringBuilder builder = new StringBuilder();
        if (mapid < 100000000) {
            builder.append("maple");
        } else if ((mapid >= 100000000 && mapid < 200000000) || (mapid >= 749080172 && mapid <= 749080254)
                || (mapid >= 862000000 && mapid <= 862000004) || mapid == 910050100 || mapid == 910240000
                || mapid == 910510002) {
            builder.append("victoria");
        } else if ((mapid >= 200000000 && mapid < 300000000) || mapid == 79010000) {
            builder.append("ossyria");
        } else if ((mapid >= 300000000 && mapid < 400000000) || (mapid >= 749080507 && mapid <= 749080540)) {
            builder.append("3rd");
        } else if ((mapid >= 400000000 && mapid < 420000000) || (mapid >= 940000000 && mapid <= 749080540)) {
            builder.append("grandis");
        } else if (mapid >= 500000000 && mapid < 510000000) {
            builder.append("thai");
        } else if (mapid >= 512000000 && mapid < 513000000) {
            builder.append("EU");
        } else if ((mapid >= 540000000 && mapid < 541030000) || (mapid >= 555000000 && mapid <= 940029000)) {
            builder.append("SG");
        } else if (mapid >= 600000000 && mapid < 600030000) {
            builder.append("MasteriaGL");
        } else if (mapid >= 677000000 && mapid < 678000000) {
            builder.append("Episode1GL");
        } else if (mapid >= 680000000 && mapid <= 680000800) {
            builder.append("global");
        } else if (mapid >= 670000000 && mapid < 682000000) {
            builder.append("weddingGL");
        } else if (mapid >= 682000000 && mapid < 683000000) {
            builder.append("HalloweenGL");
        } else if (mapid >= 683000000 && mapid < 684000000) {
            builder.append("event");
        } else if (mapid >= 684000000 && mapid < 685000000) {
            builder.append("event_5th");
        } else if (mapid >= 686000000 && mapid < 687000000) {
            builder.append("event_6th");
        } else if (mapid >= 687000000 && mapid < 688000000) {
            builder.append("Gacha_GL");
        } else if (mapid >= 689010000 && mapid < 689013089) {
            builder.append("Pink ZakumGL");
        } else if (mapid >= 689000000 && mapid <= 689000010) {
            builder.append("CTF_GL");
        } else if ((mapid >= 690010000 && mapid < 700000000) || (mapid >= 746000001 && mapid <= 746000018)) {
            builder.append("boost");
        } else if ((mapid >= 743000000 && mapid < 744000000) || (mapid >= 744000020 && mapid <= 744000041)
                || (mapid >= 745000000 && mapid < 746000000) || (mapid >= 749050900 && mapid < 749060000)
                || (mapid >= 749080142 && mapid <= 749080600)) {
            builder.append("taiwan");
        } else if (mapid >= 700000000 && mapid < 790000000) {
            builder.append("chinese");
        } else if (mapid >= 802001100 && mapid <= 802001700) {
            builder.append("NeoTokyo2014");
        } else if (mapid >= 811000000 && mapid <= 811000999) {
            builder.append("Sengoku2014");
        } else if ((mapid >= 863000000 && mapid < 864000000) || (mapid >= 865000000 && mapid < 867000000)) {
            builder.append("dawnveil");
        } else if (mapid >= 860000000 && mapid < 870000000) {
            builder.append("aquaroad");
        } else if (mapid >= 800000000 && mapid < 900000000) {
            builder.append("jp");
        } else {
            builder.append("etc");
        }
        builder.append("/");
        builder.append(mapid);

        return builder.toString();
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    private void addAreaBossSpawn(final MapleMap map) {
        int monsterid = -1;
        int mobtime = -1;
        String msg = null;
        boolean shouldSpawn = true;
        Point pos1 = null, pos2 = null, pos3 = null;

        switch (map.getId()) {
            case 104010200: // Mano
                mobtime = 1200;
                monsterid = 2220000;
                msg = "天氣涼快了就會出現紅寶王。";
                pos1 = new Point(110, 35);
                break;
            case 102020500: // Stumpy
                mobtime = 1200;
                monsterid = 3220000;
                msg = "樹妖王出現了。";
                pos1 = new Point(1121, 2145);
                break;
            case 100020101: // Mushmom
                mobtime = 1200;
                monsterid = 9300671;
                msg = "什麼地方出現了巨大的蘑菇。";
                pos1 = new Point(-330, 215);
                break;
            case 100020301: // Blue Mushmom
                mobtime = 1200;
                monsterid = 8220007;
                msg = "什麼地方出現了巨大的藍色蘑菇。";
                pos1 = new Point(-190, -625);
                break;
            case 105010301: // Zombie Mushmom
                mobtime = 1200;
                monsterid = 6300005;
                msg = "什麼地方出現了籠罩著陰暗氣息的巨大蘑菇。";
                pos1 = new Point(2880, 125);
                break;
            case 120030500: // King Clang
                mobtime = 1200;
                monsterid = 5220001;
                msg = "從沙灘里慢慢走出一隻巨居蟹。";
                pos1 = new Point(-355, 179);
                pos2 = new Point(-1283, -113);
                pos3 = new Point(-571, -593);
                break;
            case 250010304: // Tae Roon
                mobtime = 2100;
                monsterid = 7220000;
                msg = "隨著微弱的口哨聲，肯德熊出現了。";
                pos1 = new Point(-234, 393);
                break;
            case 200010302: // Eliza
                mobtime = 1200;
                monsterid = 8220000;
                msg = "艾利傑出現了。";
                pos1 = new Point(300, 80);
                break;
            case 250010503: // Ghost Priest
                mobtime = 1800;
                monsterid = 7220002;
                msg = "周邊的妖氣慢慢濃厚，可以聽到詭異的貓叫聲。";
                pos1 = new Point(-303, 543);
                pos2 = new Point(227, 543);
                pos3 = new Point(719, 543);
                break;
            case 222010310: // Old Fox
                mobtime = 2700;
                monsterid = 7220001;
                msg = "在陰暗的月光中隨著九尾妖狐的哭聲，可以感受到它陰氣。";
                pos1 = new Point(-169, -147);
                pos2 = new Point(-517, 93);
                pos3 = new Point(247, 93);
                break;
            case 103030400: // Dale
                mobtime = 1800;
                monsterid = 6220000;
                msg = "從沼澤出現了巨大的沼澤巨鱷。";
                pos1 = new Point(270, 120);
                pos2 = new Point(-530, 120);
                break;
            case 101040300: // Faust
                mobtime = 1800;
                monsterid = 5220002;
                msg = "藍霧慢慢散去，殭屍猴王慢慢的顯現了出來。";
                pos1 = new Point(600, -600);
                pos2 = new Point(600, -800);
                pos3 = new Point(600, -300);
                break;
            case 220050200: // Timer
                mobtime = 1500;
                monsterid = 5220003;
                msg = "嘀嗒嘀嗒！隨著規則的指針聲出現了咕咕鐘。";
                pos1 = new Point(-400, 252);
                break;
            case 221040301: // Zeno
                mobtime = 2400;
                monsterid = 6220001;
                msg = "厚重的機器運作聲，葛雷金剛出現了！";
                pos1 = new Point(-4134, 416);
                pos2 = new Point(-4283, 776);
                pos3 = new Point(-3292, 776);
                break;
            case 240040401: // Lev
                mobtime = 7200;
                monsterid = 8220003;
                msg = "寒霜冰龍出現了。";
                pos1 = new Point(-15, 2481);
                pos2 = new Point(127, 1634);
                pos3 = new Point(159, 1142);
                break;
            case 260010500: // Deo
                mobtime = 3600;
                monsterid = 3220001;
                msg = "從沙塵中可以看到仙人長老的身影。";
                pos1 = new Point(290, -325);
                break;
            case 251010102: // Centipede
                mobtime = 3600;
                monsterid = 5220004;
                msg = "蜈蚣大王出現了。";
                pos1 = new Point(-41, 124);
                pos2 = new Point(-173, 126);
                pos3 = new Point(79, 118);
                break;
            case 261030000: // Chimera
                mobtime = 2700;
                monsterid = 8220002;
                msg = "奇美拉出現了。";
                pos1 = new Point(-1094, -405);
                pos2 = new Point(-772, -116);
                pos3 = new Point(-108, 181);
                break;
            case 230020100: // Sherp
                mobtime = 2700;
                monsterid = 4220000;
                msg = "在海草中間，出現了奇怪的火蚌殼。";
                pos1 = new Point(-291, -20);
                pos2 = new Point(-272, -500);
                pos3 = new Point(-462, 640);
                break;
            case 103020320: // Shade
                mobtime = 1800;
                monsterid = 5090000;
                msg = "在地鐵的陰影中出現了什麼東西。";
                pos1 = new Point(79, 174);
                pos2 = new Point(-223, 296);
                pos3 = new Point(80, 275);
                break;
            case 103020420: // Shade
                mobtime = 1800;
                monsterid = 5090000;
                msg = "在地鐵的陰影中出現了什麼東西。";
                pos1 = new Point(2241, 301);
                pos2 = new Point(1990, 301);
                pos3 = new Point(1684, 307);
                break;
            case 261020300: // Camera
                mobtime = 2700;
                monsterid = 7090000;
                msg = "自動警備系統出現了。";
                pos1 = new Point(312, 157);
                pos2 = new Point(539, 136);
                pos3 = new Point(760, 141);
                break;
            case 261020401: // Deet and Roi
                mobtime = 2700;
                monsterid = 8090000;
                msg = "迪特和洛依出現了。";
                pos1 = new Point(-263, 155);
                pos2 = new Point(-436, 122);
                pos3 = new Point(22, 144);
                break;
            case 250020300: // Master Dummy
                mobtime = 2700;
                monsterid = 5090001;
                msg = "仙人娃娃出現了。";
                pos1 = new Point(1208, 27);
                pos2 = new Point(1654, 40);
                pos3 = new Point(927, -502);
                break;
            case 211050000: // Snow Witch
                mobtime = 2700;
                monsterid = 6090001;
                msg = "被束縛在冰裡的魔女睜開了眼睛。";
                pos1 = new Point(-233, -431);
                pos2 = new Point(-370, -426);
                pos3 = new Point(-526, -420);
                break;
            case 261010003: // Rurumo
                mobtime = 2700;
                monsterid = 6090004;
                msg = "紅藍雙怪出現了。";
                pos1 = new Point(-861, 301);
                pos2 = new Point(-703, 301);
                pos3 = new Point(-426, 287);
                break;
            case 222010300: // Scholar Ghost
                mobtime = 2700;
                monsterid = 6090003;
                msg = "書生幽靈出現了。";
                pos1 = new Point(1300, -400);
                pos2 = new Point(1100, -100);
                pos3 = new Point(1100, 100);
                break;
            case 251010101: // Bamboo Warrior
                mobtime = 2700;
                monsterid = 6090002;
                msg = "竹林里出現了一個來歷不明的竹刀武士，只要打碎小竹片，就可以讓竹刀武士大發雷霆而葬失自制力，並將它打倒。";
                pos1 = new Point(-15, -449);
                pos2 = new Point(-114, -442);
                pos3 = new Point(-255, -446);
                break;
            case 211041400: // Riche
                mobtime = 2700;
                monsterid = 6090000;
                msg = "厄運死神出現了！";
                pos1 = new Point(1672, 82);
                pos2 = new Point(2071, 10);
                pos3 = new Point(1417, 57);
                break;
            case 105030500: // Rog
                mobtime = 2700;
                monsterid = 8130100;
                msg = "巴洛古出現了。";
                pos1 = new Point(1275, -399);
                pos2 = new Point(1254, -412);
                pos3 = new Point(1058, -427);
                break;
            case 105020400: // Snack Bar
                mobtime = 2700;
                monsterid = 8220008;
                msg = "出現了一個不知名的路邊攤。";
                pos1 = new Point(-163, 82);
                pos2 = new Point(958, 107);
                pos3 = new Point(706, -206);
                break;
            case 211040101: // Snowman
                mobtime = 3600;
                monsterid = 8220001;
                msg = "雪毛怪人出現了。";
                pos1 = new Point(485, 244);
                pos2 = new Point(-60, 249);
                pos3 = new Point(208, 255);
                break;
            case 209000000: // Happyville
                mobtime = 300;
                monsterid = 9500318;
                msg = "生氣的雪人出現了。";
                pos1 = new Point(-115, 154);
                pos2 = new Point(-115, 154);
                pos3 = new Point(-115, 154);
                break;
            case 677000001:
                mobtime = 60;
                monsterid = 9400612;
                msg = "瑪巴斯出現了。";
                pos1 = new Point(99, 60);
                pos2 = new Point(99, 60);
                pos3 = new Point(99, 60);
                break;
            case 677000003:
                mobtime = 60;
                monsterid = 9400610;
                msg = "安督西亞出現了。";
                pos1 = new Point(6, 35);
                pos2 = new Point(6, 35);
                pos3 = new Point(6, 35);
                break;
            case 677000005:
                mobtime = 60;
                monsterid = 9400609;
                msg = "安卓斯出現了。";
                pos1 = new Point(-277, 78); // on the spawnpoint
                pos2 = new Point(547, 86); // bottom of right ladder
                pos3 = new Point(-347, 80); // bottom of left ladder
                break;
            case 677000007:
                mobtime = 60;
                monsterid = 9400611;
                msg = "克羅賽爾出現了。";
                pos1 = new Point(117, 73);
                pos2 = new Point(117, 73);
                pos3 = new Point(117, 73);
                break;
            case 677000009:
                mobtime = 60;
                monsterid = 9400613;
                msg = "華利弗出現了。";
                pos1 = new Point(85, 66);
                pos2 = new Point(85, 66);
                pos3 = new Point(85, 66);
                break;
            case 931000500:
            case 931000502:
                mobtime = 3600;
                monsterid = 9304005;
                msg = "美洲豹棲息地出現 杰拉。";
                pos1 = new Point(-872, -332);
                pos2 = new Point(409, -572);
                pos3 = new Point(-131, 0);
                shouldSpawn = false;
                break;
            case 931000501:
            case 931000503:
                mobtime = 7200;
                monsterid = 9304006;
                msg = "美洲豹棲息地出現 白雪。";
                pos1 = new Point(-872, -332);
                pos2 = new Point(409, -572);
                pos3 = new Point(-131, 0);
                shouldSpawn = false;
                break;
        }
        if (monsterid > 0) {
            map.addAreaMonsterSpawn(MapleLifeFactory.getMonster(monsterid), pos1, pos2, pos3, mobtime, msg,
                    shouldSpawn);
        }
    }

    private void loadPortals(MapleMap map, MapleData port) {
        if (port == null) {
            return;
        }
        int nextDoorPortal = 0x80;
        for (MapleData portal : port.getChildren()) {
            MaplePortal myPortal = new MaplePortal(MapleDataTool.getInt(portal.getChildByPath("pt")));
            myPortal.setName(MapleDataTool.getString(portal.getChildByPath("pn")));
            myPortal.setTarget(MapleDataTool.getString(portal.getChildByPath("tn"), null));
            myPortal.setTargetMapId(MapleDataTool.getInt(portal.getChildByPath("tm")));
            myPortal.setPosition(new Point(MapleDataTool.getInt(portal.getChildByPath("x")),
                    MapleDataTool.getInt(portal.getChildByPath("y"))));
            String script = MapleDataTool.getString("script", portal, null);
            if (script != null && script.equals("")) {
                script = null;
            }
            myPortal.setScriptName(script);

            if (myPortal.getType() == MaplePortal.DOOR_PORTAL) {
                myPortal.setId(nextDoorPortal);
                nextDoorPortal++;
            } else {
                myPortal.setId(Integer.parseInt(portal.getName()));
            }
            map.addPortal(myPortal);
        }
    }

    private MapleNodes loadNodes(final int mapid, final MapleData mapData) {
        MapleNodes nodeInfo = new MapleNodes(mapid);
        if (mapData.getChildByPath("nodeInfo") != null) {
            for (MapleData node : mapData.getChildByPath("nodeInfo").getChildren()) {
                try {
                    if (node.getName().equals("start")) {
                        nodeInfo.setNodeStart(MapleDataTool.getInt(node, 0));
                        continue;
                    }
                    List<Integer> edges = new ArrayList<>();
                    if (node.getChildByPath("edge") != null) {
                        for (MapleData edge : node.getChildByPath("edge").getChildren()) {
                            edges.add(MapleDataTool.getInt(edge, -1));
                        }
                    }
                    final MapleNodeInfo mni = new MapleNodeInfo(Integer.parseInt(node.getName()),
                            MapleDataTool.getIntConvert("key", node, 0), MapleDataTool.getIntConvert("x", node, 0),
                            MapleDataTool.getIntConvert("y", node, 0), MapleDataTool.getIntConvert("attr", node, 0),
                            edges);
                    nodeInfo.addNode(mni);
                } catch (NumberFormatException e) {
                } // start, end, edgeInfo = we dont need it
            }
            nodeInfo.sortNodes();
        }
        for (int i = 1; i <= 7; i++) {
            if (mapData.getChildByPath(String.valueOf(i)) != null && mapData.getChildByPath(i + "/obj") != null) {
                for (MapleData node : mapData.getChildByPath(i + "/obj").getChildren()) {
                    if (node.getChildByPath("SN_count") != null && node.getChildByPath("speed") != null) {
                        int sn_count = MapleDataTool.getIntConvert("SN_count", node, 0);
                        String name = MapleDataTool.getString("name", node, "");
                        int speed = MapleDataTool.getIntConvert("speed", node, 0);
                        if (sn_count <= 0 || speed <= 0 || name.equals("")) {
                            continue;
                        }
                        final List<Integer> SN = new ArrayList<>();
                        for (int x = 0; x < sn_count; x++) {
                            SN.add(MapleDataTool.getIntConvert("SN" + x, node, 0));
                        }
                        final MaplePlatform mni = new MaplePlatform(name, MapleDataTool.getIntConvert("start", node, 2),
                                speed, MapleDataTool.getIntConvert("x1", node, 0),
                                MapleDataTool.getIntConvert("y1", node, 0), MapleDataTool.getIntConvert("x2", node, 0),
                                MapleDataTool.getIntConvert("y2", node, 0), MapleDataTool.getIntConvert("r", node, 0),
                                SN);
                        nodeInfo.addPlatform(mni);
                    } else if (node.getChildByPath("tags") != null) {
                        String name = MapleDataTool.getString("tags", node, "");
                        nodeInfo.addFlag(new Pair<>(name, name.endsWith("3") ? 1 : 0)); // idk, no indication in wz
                    }
                }
            }
        }
        // load areas (EG PQ platforms)
        if (mapData.getChildByPath("area") != null) {
            int x1, y1, x2, y2;
            Rectangle mapArea;
            for (MapleData area : mapData.getChildByPath("area").getChildren()) {
                x1 = MapleDataTool.getInt(area.getChildByPath("x1"));
                y1 = MapleDataTool.getInt(area.getChildByPath("y1"));
                x2 = MapleDataTool.getInt(area.getChildByPath("x2"));
                y2 = MapleDataTool.getInt(area.getChildByPath("y2"));
                mapArea = new Rectangle(x1, y1, (x2 - x1), (y2 - y1));
                nodeInfo.addMapleArea(mapArea);
            }
        }
        if (mapData.getChildByPath("CaptureTheFlag") != null) {
            final MapleData mc = mapData.getChildByPath("CaptureTheFlag");
            for (MapleData area : mc.getChildren()) {
                nodeInfo.addGuardianSpawn(
                        new Point(MapleDataTool.getInt(area.getChildByPath("FlagPositionX")),
                                MapleDataTool.getInt(area.getChildByPath("FlagPositionY"))),
                        area.getName().startsWith("Red") ? 0 : 1);
            }
        }
        if (mapData.getChildByPath("directionInfo") != null) {
            final MapleData mc = mapData.getChildByPath("directionInfo");
            for (MapleData area : mc.getChildren()) {
                DirectionInfo di = new DirectionInfo(Integer.parseInt(area.getName()),
                        MapleDataTool.getInt("x", area, 0), MapleDataTool.getInt("y", area, 0),
                        MapleDataTool.getInt("forcedInput", area, 0) > 0);
                final MapleData mc2 = area.getChildByPath("EventQ");
                if (mc2 != null) {
                    for (MapleData event : mc2.getChildren()) {
                        di.eventQ.add(MapleDataTool.getString(event));
                    }
                }
                nodeInfo.addDirection(Integer.parseInt(area.getName()), di);
            }
        }
        if (mapData.getChildByPath("monsterCarnival") != null) {
            final MapleData mc = mapData.getChildByPath("monsterCarnival");
            if (mc.getChildByPath("mobGenPos") != null) {
                for (MapleData area : mc.getChildByPath("mobGenPos").getChildren()) {
                    nodeInfo.addMonsterPoint(MapleDataTool.getInt(area.getChildByPath("x")),
                            MapleDataTool.getInt(area.getChildByPath("y")),
                            MapleDataTool.getInt(area.getChildByPath("fh")),
                            MapleDataTool.getInt(area.getChildByPath("cy")), MapleDataTool.getInt("team", area, -1));
                }
            }
            if (mc.getChildByPath("mob") != null) {
                for (MapleData area : mc.getChildByPath("mob").getChildren()) {
                    nodeInfo.addMobSpawn(MapleDataTool.getInt(area.getChildByPath("id")),
                            MapleDataTool.getInt(area.getChildByPath("spendCP")));
                }
            }
            if (mc.getChildByPath("guardianGenPos") != null) {
                for (MapleData area : mc.getChildByPath("guardianGenPos").getChildren()) {
                    nodeInfo.addGuardianSpawn(
                            new Point(MapleDataTool.getInt(area.getChildByPath("x")),
                                    MapleDataTool.getInt(area.getChildByPath("y"))),
                            MapleDataTool.getInt("team", area, -1));
                }
            }
            if (mc.getChildByPath("skill") != null) {
                for (MapleData area : mc.getChildByPath("skill").getChildren()) {
                    nodeInfo.addSkillId(MapleDataTool.getInt(area));
                }
            }
        }
        return nodeInfo;
    }
}
