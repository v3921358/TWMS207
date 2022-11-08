package server;

import client.MapleCharacter;
import client.MapleJob;
import client.MapleTrait.MapleTraitType;
import client.inventory.Equip;
import client.inventory.EquipStat;
import client.inventory.Item;
import client.inventory.ItemFlag;
import client.inventory.MapleInventoryType;
import client.inventory.MaplePet;
import constants.GameConstants;
import constants.ItemConstants;
import database.ManagerDatabasePool;
import handling.channel.handler.EquipmentEnchant;
import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;

import provider.MapleData;
import provider.MapleDataDirectoryEntry;
import provider.MapleDataEntry;
import provider.MapleDataFileEntry;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import provider.MapleDataType;
import server.StructSetItem.SetItem;
import server.farm.inventory.FarmItemInformation;
import tools.Pair;
import tools.Triple;
import tools.packet.CWvsContext;

public class MapleItemInformationProvider {

    private final static MapleItemInformationProvider instance = new MapleItemInformationProvider();
    protected final MapleDataProvider chrData = MapleDataProviderFactory.getDataProvider("Character");
    protected final MapleDataProvider etcData = MapleDataProviderFactory.getDataProvider("Etc");
    protected final MapleDataProvider itemData = MapleDataProviderFactory.getDataProvider("Item");
    protected final MapleDataProvider stringData = MapleDataProviderFactory.getDataProvider("String");
    protected final Map<Integer, ItemInformation> dataCache = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, Short> petFlagInfo = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, FarmItemInformation> farmDataCache = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<String, List<Triple<String, Point, Point>>> afterImage = new HashMap<>();
    protected final Map<Integer, List<StructItemOption>> potentialCache = new HashMap<>();
    protected final Map<Integer, Map<Integer, StructItemOption>> socketCache = new HashMap<>(); // Grade, (id, data)
    protected final Map<Integer, MapleStatEffect> itemEffects = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, MapleStatEffect> itemEffectsEx = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, Integer> mobIds = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, Pair<Integer, Integer>> potLife = new HashMap<>(); // itemid to lifeid, levels
    protected final Map<Integer, StructFamiliar> familiars = new HashMap<>(); // by familiarID
    protected final Map<Integer, StructFamiliar> familiars_Item = new HashMap<>(); // by cardID
    protected final Map<Integer, StructFamiliar> familiars_Mob = new HashMap<>(); // by mobID
    protected final Map<Integer, Map<Integer, Integer>> familiarTable_rchance = new HashMap<>();
    protected final Map<Integer, Map<Integer, Float>> familiarTable_pad = new HashMap<>();
    protected final Map<Integer, Integer> familiarTable_fee_reinforce = new HashMap<>();
    protected final Map<Integer, Integer> familiarTable_fee_evolve = new HashMap<>();
    protected final Map<Integer, List<StructItemOption>> familiar_option = new HashMap<>();

    protected final Map<Integer, Triple<String, List, SetItem>> familiarSets = new HashMap<>();

    protected Map<Integer, Integer> androidType = new TreeMap<>(Comparator.naturalOrder());
    protected Map<Integer, StructAndroid> androidInfo = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, Triple<Integer, List<Integer>, List<Integer>>> monsterBookSets = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, StructSetItem> setItems = new HashMap<>();
    protected final Map<Integer, Boolean> noCursedScroll = new HashMap<>();
    protected final Map<Integer, int[]> expPotionLev = new HashMap<>();
    protected final Map<Integer, List<Integer>> canAccountSharable = new HashMap<>();
    protected final Map<Integer, List<Integer>> cantAccountSharable = new HashMap<>();
    protected final Map<Integer, Boolean> noNegativeScroll = new HashMap<>();
    protected final Map<Integer, Integer> forceUpgrade = new HashMap<>();
    protected final Map<Integer, Integer> npcs = new HashMap<>();
    protected Map<Integer, Pair<Integer, Integer>> chairRecovery = new HashMap<>();
    protected Map<Integer, Map<String, String>> expCardTimes = new HashMap<>();
    protected final Map<Integer, Pair<Integer, Integer>> socketReqLevel = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, Integer> soulSkill = new TreeMap<>(Comparator.naturalOrder());
    protected final Map<Integer, ArrayList<Integer>> tempOption = new TreeMap<>(Comparator.naturalOrder());

    public void runEtc(boolean reload) {
        if (reload) {
            setItems.clear();
            potentialCache.clear();
            socketCache.clear();
            androidInfo.clear();
            potLife.clear();
            afterImage.clear();
        }
        if (!setItems.isEmpty() || !potentialCache.isEmpty() || !socketCache.isEmpty() || !androidInfo.isEmpty()
                || !potLife.isEmpty() || !afterImage.isEmpty()) {
            return;
        }

        final MapleData setsData = etcData.getData("SetItemInfo.img");
        StructSetItem itemz;
        SetItem itez;
        for (MapleData dat : setsData.getChildren()) {
            itemz = new StructSetItem();
            itemz.setItemID = Integer.parseInt(dat.getName());
            if (itemz.setItemID == 0) {
                continue;
            }
            itemz.setItemName = MapleDataTool.getString("setItemName", dat, "未命名");
            itemz.completeCount = (byte) MapleDataTool.getIntConvert("completeCount", dat, 0);
            for (MapleData level : dat.getChildByPath("ItemID").getChildren()) {
                if (level.getType() != MapleDataType.INT) {
                    for (MapleData leve : level.getChildren()) {
                        if (!leve.getName().equals("representName") && !leve.getName().equals("typeName")) {
                            itemz.itemIDs.add(MapleDataTool./* getInt */getIntConvert(leve));
                        }
                    }
                } else {
                    itemz.itemIDs.add(MapleDataTool.getInt(level));
                }
            }
            for (MapleData level : dat.getChildByPath("Effect").getChildren()) {
                itez = new SetItem();
                itez.incPDD = MapleDataTool.getIntConvert("incPDD", level, 0);
                itez.incMDD = MapleDataTool.getIntConvert("incMDD", level, 0);
                itez.incSTR = MapleDataTool.getIntConvert("incSTR", level, 0);
                itez.incDEX = MapleDataTool.getIntConvert("incDEX", level, 0);
                itez.incINT = MapleDataTool.getIntConvert("incINT", level, 0);
                itez.incLUK = MapleDataTool.getIntConvert("incLUK", level, 0);
                itez.incACC = MapleDataTool.getIntConvert("incACC", level, 0);
                itez.incPAD = MapleDataTool.getIntConvert("incPAD", level, 0);
                itez.incMAD = MapleDataTool.getIntConvert("incMAD", level, 0);
                itez.incSpeed = MapleDataTool.getIntConvert("incSpeed", level, 0);
                itez.incJump = MapleDataTool.getIntConvert("incJump", level, 0);
                itez.incMHP = MapleDataTool.getIntConvert("incMHP", level, 0);
                itez.incMMP = MapleDataTool.getIntConvert("incMMP", level, 0);
                itez.incMHPr = MapleDataTool.getIntConvert("incMHPr", level, 0);
                itez.incMMPr = MapleDataTool.getIntConvert("incMMPr", level, 0);
                itez.incAllStat = MapleDataTool.getIntConvert("incAllStat", level, 0);
                itez.option1 = MapleDataTool.getIntConvert("Option/1/option", level, 0);
                itez.option2 = MapleDataTool.getIntConvert("Option/2/option", level, 0);
                itez.option1Level = MapleDataTool.getIntConvert("Option/1/level", level, 0);
                itez.option2Level = MapleDataTool.getIntConvert("Option/2/level", level, 0);
                itemz.items.put(Integer.parseInt(level.getName()), itez);
            }
            setItems.put(itemz.setItemID, itemz);
        }
        StructItemOption item;
        final MapleData potsData = itemData.getData("ItemOption.img");
        List<StructItemOption> items;
        for (MapleData dat : potsData.getChildren()) {
            items = new LinkedList<>();
            for (MapleData potLevel : dat.getChildByPath("level").getChildren()) {
                item = new StructItemOption();
                item.opID = Integer.parseInt(dat.getName());
                item.optionType = MapleDataTool.getIntConvert("info/optionType", dat, 0);
                item.string = MapleDataTool.getString("info/string", dat, "");
                item.reqLevel = MapleDataTool.getIntConvert("info/reqLevel", dat, 0);
                for (final String i : StructItemOption.types) {
                    if (i.equals("face")) {
                        item.face = MapleDataTool.getString("face", potLevel, "");
                    } else {
                        final int level = MapleDataTool.getIntConvert(i, potLevel, 0);
                        if (level > 0) { // Save memory
                            item.data.put(i, level);
                        }
                    }
                }
                switch (item.opID) {
                    case 31001: // Haste
                    case 31002: // Mystic Door
                    case 31003: // Sharp Eyes
                    case 31004: // Hyper Body
                        item.data.put("skillID", (item.opID - 23001));
                        break;
                    case 41005: // Combat Orders
                    case 41006: // Advanced Blessing
                    case 41007: // Speed Infusion
                        item.data.put("skillID", (item.opID - 33001));
                        break;
                }
                items.add(item);
            }
            potentialCache.put(Integer.parseInt(dat.getName()), items);
        }
        final Map<Integer, StructItemOption> gradeS = new HashMap<>();
        final Map<Integer, StructItemOption> gradeA = new HashMap<>();
        final Map<Integer, StructItemOption> gradeB = new HashMap<>();
        final Map<Integer, StructItemOption> gradeC = new HashMap<>();
        final Map<Integer, StructItemOption> gradeD = new HashMap<>();

        /*
         * final MapleData nebuliteData = itemData.getData("Install/0306.img"); for
         * (MapleData dat : nebuliteData) { item = new StructItemOption(); item.opID =
         * Integer.parseInt(dat.getName()); // Item Id item.optionType =
         * MapleDataTool.getInt("optionType", dat.getChildByPath("socket"), 0); for
         * (MapleData info : dat.getChildByPath("socket/option").getChildren()) { final
         * String optionString = MapleDataTool.getString("optionString", info, "");
         * final int level = MapleDataTool.getInt("level", info, 0); if (level > 0) { //
         * Save memory item.data.put(optionString, level); } } switch (item.opID) { case
         * 3063370: // Haste item.data.put("skillID", 8000); break; case 3063380: //
         * Mystic Door item.data.put("skillID", 8001); break; case 3063390: // Sharp
         * Eyes item.data.put("skillID", 8002); break; case 3063400: // Hyper Body
         * item.data.put("skillID", 8003); break; case 3064470: // Combat Orders
         * item.data.put("skillID", 8004); break; case 3064480: // Advanced Blessing
         * item.data.put("skillID", 8005); break; case 3064490: // Speed Infusion
         * item.data.put("skillID", 8006); break; } switch
         * (GameConstants.getNebuliteGrade(item.opID)) { case 4: //S
         * gradeS.put(Integer.parseInt(dat.getName()), item); break; case 3: //A
         * gradeA.put(Integer.parseInt(dat.getName()), item); break; case 2: //B
         * gradeB.put(Integer.parseInt(dat.getName()), item); break; case 1: //C
         * gradeC.put(Integer.parseInt(dat.getName()), item); break; case 0: //D
         * gradeD.put(Integer.parseInt(dat.getName()), item); break; // impossible to be
         * -1 since we're looping in 306.img.xml } }
         */
        socketCache.put(4, gradeS);
        socketCache.put(3, gradeA);
        socketCache.put(2, gradeB);
        socketCache.put(1, gradeC);
        socketCache.put(0, gradeD);

        final MapleDataDirectoryEntry e = (MapleDataDirectoryEntry) etcData.getRoot().getEntry("Android");
        for (MapleDataEntry d : e.getFiles()) {
            final MapleData iz = etcData.getData("Android/" + d.getName());
            StructAndroid android = new StructAndroid();
            int type = Integer.parseInt(d.getName().substring(0, 4));
            android.type = type;
            android.gender = MapleDataTool.getIntConvert("info/gender", iz, 0);
            for (MapleData ds : iz.getChildByPath("costume/skin").getChildren()) {
                android.skin.add(MapleDataTool.getInt(ds, 2000));
            }
            for (MapleData ds : iz.getChildByPath("costume/hair").getChildren()) {
                android.hair.add(MapleDataTool.getInt(ds, android.gender == 0 ? 20101 : 21101));
            }
            for (MapleData ds : iz.getChildByPath("costume/face").getChildren()) {
                android.face.add(MapleDataTool.getInt(ds, android.gender == 0 ? 30110 : 31510));
            }
            androidInfo.put(type, android);
        }

        final MapleData lifesData = etcData.getData("ItemPotLifeInfo.img");
        for (MapleData d : lifesData.getChildren()) {
            if (d.getChildByPath("info") != null && MapleDataTool.getInt("type", d.getChildByPath("info"), 0) == 1) {
                potLife.put(MapleDataTool.getInt("counsumeItem", d.getChildByPath("info"), 0),
                        new Pair<>(Integer.parseInt(d.getName()), d.getChildByPath("level").getChildren().size()));
            }
        }
        List<Triple<String, Point, Point>> thePointK = new ArrayList<>();
        List<Triple<String, Point, Point>> thePointA = new ArrayList<>();

        final MapleDataDirectoryEntry a = (MapleDataDirectoryEntry) chrData.getRoot().getEntry("Afterimage");
        for (MapleDataEntry b : a.getFiles()) {
            final MapleData iz = chrData.getData("Afterimage/" + b.getName());
            List<Triple<String, Point, Point>> thePoint = new ArrayList<>();
            Map<String, Pair<Point, Point>> dummy = new HashMap<>();
            for (MapleData i : iz.getChildren()) {
                for (MapleData xD : i.getChildren()) {
                    if (xD.getName().contains("prone") || xD.getName().contains("double")
                            || xD.getName().contains("triple")) {
                        continue;
                    }
                    if ((b.getName().contains("bow") || b.getName().contains("Bow"))
                            && !xD.getName().contains("shoot")) {
                        continue;
                    }
                    if ((b.getName().contains("gun") || b.getName().contains("cannon"))
                            && !xD.getName().contains("shot")) {
                        continue;
                    }
                    if (dummy.containsKey(xD.getName())) {
                        if (xD.getChildByPath("lt") != null) {
                            Point lt = (Point) xD.getChildByPath("lt").getData();
                            Point ourLt = dummy.get(xD.getName()).left;
                            if (lt.x < ourLt.x) {
                                ourLt.x = lt.x;
                            }
                            if (lt.y < ourLt.y) {
                                ourLt.y = lt.y;
                            }
                        }
                        if (xD.getChildByPath("rb") != null) {
                            Point rb = (Point) xD.getChildByPath("rb").getData();
                            Point ourRb = dummy.get(xD.getName()).right;
                            if (rb.x > ourRb.x) {
                                ourRb.x = rb.x;
                            }
                            if (rb.y > ourRb.y) {
                                ourRb.y = rb.y;
                            }
                        }
                    } else {
                        Point lt = null, rb = null;
                        if (xD.getChildByPath("lt") != null) {
                            lt = (Point) xD.getChildByPath("lt").getData();
                        }
                        if (xD.getChildByPath("rb") != null) {
                            rb = (Point) xD.getChildByPath("rb").getData();
                        }
                        dummy.put(xD.getName(), new Pair<>(lt, rb));
                    }
                }
            }
            for (Entry<String, Pair<Point, Point>> ez : dummy.entrySet()) {
                if (ez.getKey().length() > 2
                        && ez.getKey().substring(ez.getKey().length() - 2, ez.getKey().length() - 1).equals("D")) { // D
                    // =
                    // double
                    // weapon
                    thePointK.add(new Triple<>(ez.getKey(), ez.getValue().left, ez.getValue().right));
                } else if (ez.getKey().contains("PoleArm")) { // D = double weapon
                    thePointA.add(new Triple<>(ez.getKey(), ez.getValue().left, ez.getValue().right));
                } else {
                    thePoint.add(new Triple<>(ez.getKey(), ez.getValue().left, ez.getValue().right));
                }
            }
            afterImage.put(b.getName().substring(0, b.getName().length() - 4), thePoint);
        }
        afterImage.put("katara", thePointK); // hackish
        afterImage.put("aran", thePointA); // hackish
    }

    public void runItems(boolean reload) {
        if (reload) {
            dataCache.clear();
        }
        if (!dataCache.isEmpty()) {
            return;
        }
        this.runFamiliar();
        try {
            // Load Item Data
            Connection con = ManagerDatabasePool.getConnection();
            // Load Item Data
            PreparedStatement ps = con.prepareStatement("SELECT * FROM wz_itemdata");

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                initItemInformation(rs);
            }
            rs.close();
            ps.close();

            // Load Item Equipment Data
            ps = con.prepareStatement("SELECT * FROM wz_itemequipdata ORDER BY itemid");
            rs = ps.executeQuery();
            while (rs.next()) {
                initItemEquipData(rs);
            }
            rs.close();
            ps.close();

            // Load Item Addition Data
            ps = con.prepareStatement("SELECT * FROM wz_itemadddata ORDER BY itemid");
            rs = ps.executeQuery();
            while (rs.next()) {
                initItemAddData(rs);
            }
            rs.close();
            ps.close();

            // Load Item Reward Data
            ps = con.prepareStatement("SELECT * FROM wz_itemrewarddata ORDER BY itemid");
            rs = ps.executeQuery();
            while (rs.next()) {
                initItemRewardData(rs);
            }
            rs.close();
            ps.close();

            // Finalize all Equipments
            dataCache.entrySet().stream()
                    .filter((entry) -> (GameConstants.getInventoryType(entry.getKey()) == MapleInventoryType.EQUIP))
                    .forEach((entry) -> finalizeEquipData(entry.getValue()));
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException ex) {
        }
        // System.out.println(dataCache.size() + " items loaded.");
    }

    public final List<StructItemOption> getPotentialInfo(final int potId) {
        return potentialCache.get(potId);
    }

    public final Map<Integer, List<StructItemOption>> getAllPotentialInfo() {
        return potentialCache;
    }

    public final StructItemOption getSocketInfo(final int potId) {
        final int grade = GameConstants.getNebuliteGrade(potId);
        if (grade == -1) {
            return null;
        }
        return socketCache.get(grade).get(potId);
    }

    public final Map<Integer, StructItemOption> getAllSocketInfo(final int grade) {
        return socketCache.get(grade);
    }

    public final Collection<Integer> getMonsterBookList() {
        return mobIds.values();
    }

    public final Map<Integer, Integer> getMonsterBook() {
        return mobIds;
    }

    public final Pair<Integer, Integer> getPot(int f) {
        return potLife.get(f);
    }

    public int getFamiliarID(int monsterCardId) {
        for (Map.Entry<Integer, StructFamiliar> entry : this.familiars.entrySet()) {
            if (entry.getValue().getMonsterCardId() == monsterCardId) {
                return entry.getKey();
            }
        }
        return 0;
    }

    public final StructFamiliar getFamiliar(int f) {
        return familiars.get(f);
    }

    public final Map<Integer, StructFamiliar> getFamiliars() {
        return familiars;
    }

    public final StructFamiliar getFamiliarByItem(int f) {
        return familiars_Item.get(f);
    }

    public final StructFamiliar getFamiliarByMob(int f) {
        return familiars_Mob.get(f);
    }

    public static final MapleItemInformationProvider getInstance() {
        return instance;
    }

    public final Collection<ItemInformation> getAllItems() {
        return dataCache.values();
    }

    public final Collection<StructSetItem> getAllSetItems() {
        return setItems.values();
    }

    public final StructAndroid getAndroidInfo(int i) {
        return (StructAndroid) androidInfo.get(i);
    }

    public final Triple<Integer, List<Integer>, List<Integer>> getMonsterBookInfo(int i) {
        return monsterBookSets.get(i);
    }

    public final Map<Integer, Triple<Integer, List<Integer>, List<Integer>>> getAllMonsterBookInfo() {
        return monsterBookSets;
    }

    private final MapleData getItemData(final int itemId) {
        MapleData ret = null;
        final String idStr = "0" + String.valueOf(itemId);
        MapleDataDirectoryEntry root = itemData.getRoot();
        for (final MapleDataDirectoryEntry topDir : root.getSubdirectories()) {
            // we should have .img files here beginning with the first 4 IID
            for (final MapleDataFileEntry iFile : topDir.getFiles()) {
                if (iFile.getName().equals(idStr.substring(0, 4) + ".img")) {
                    ret = itemData.getData(topDir.getName() + "/" + iFile.getName());
                    if (ret == null) {
                        return null;
                    }
                    ret = ret.getChildByPath(idStr);
                    return ret;
                } else if (iFile.getName().equals(idStr.substring(1) + ".img")) {
                    ret = itemData.getData(topDir.getName() + "/" + iFile.getName());
                    return ret;
                }
            }
        }
        // equips dont have item effects :)
        /*
         * root = equipData.getRoot(); for (final MapleDataDirectoryEntry topDir :
         * root.getSubdirectories()) { for (final MapleDataFileEntry iFile :
         * topDir.getFiles()) { if (iFile.getName().equals(idStr + ".img")) { ret =
         * equipData.getData(topDir.getName() + "/" + iFile.getName()); return ret; } }
         * }
         */
        root = chrData.getRoot();
        for (final MapleDataDirectoryEntry topDir : root.getSubdirectories()) {
            for (final MapleDataFileEntry iFile : topDir.getFiles()) {
                if (iFile.getName().equals(idStr + ".img")) {
                    return chrData.getData(topDir.getName() + "/" + iFile.getName());
                }
            }
        }

        return ret;
    }

    public Integer getItemIdByMob(int mobId) {
        return mobIds.get(mobId);
    }

    public Integer getSetId(int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.cardSet;
    }

    public List<Pair<Integer, String>> getAllItems2() {
        // if (!itemNameCache.isEmpty()) {
        // return itemNameCache;
        // }
        List<Pair<Integer, String>> itemPairs = new ArrayList<>();
        MapleData itemsData;
        itemsData = stringData.getData("Cash.img");
        for (MapleData itemFolder : itemsData.getChildren()) {
            itemPairs.add(new Pair<>(Integer.parseInt(itemFolder.getName()),
                    MapleDataTool.getString("name", itemFolder, "NO-NAME")));
        }
        itemsData = stringData.getData("Consume.img");
        for (MapleData itemFolder : itemsData.getChildren()) {
            itemPairs.add(new Pair<>(Integer.parseInt(itemFolder.getName()),
                    MapleDataTool.getString("name", itemFolder, "NO-NAME")));
        }
        itemsData = stringData.getData("Eqp.img").getChildByPath("Eqp");
        for (MapleData eqpType : itemsData.getChildren()) {
            for (MapleData itemFolder : eqpType.getChildren()) {
                itemPairs.add(new Pair<>(Integer.parseInt(itemFolder.getName()),
                        MapleDataTool.getString("name", itemFolder, "NO-NAME")));
            }
        }
        itemsData = stringData.getData("Etc.img").getChildByPath("Etc");
        for (MapleData itemFolder : itemsData.getChildren()) {
            itemPairs.add(new Pair<>(Integer.parseInt(itemFolder.getName()),
                    MapleDataTool.getString("name", itemFolder, "NO-NAME")));
        }
        itemsData = stringData.getData("Ins.img");
        for (MapleData itemFolder : itemsData.getChildren()) {
            itemPairs.add(new Pair<>(Integer.parseInt(itemFolder.getName()),
                    MapleDataTool.getString("name", itemFolder, "NO-NAME")));
        }
        itemsData = stringData.getData("Pet.img");
        for (MapleData itemFolder : itemsData.getChildren()) {
            itemPairs.add(new Pair<>(Integer.parseInt(itemFolder.getName()),
                    MapleDataTool.getString("name", itemFolder, "NO-NAME")));
        }
        return itemPairs;
    }

    /**
     * returns the maximum of items in one slot
     *
     * @param itemId
     * @return
     */
    public final short getSlotMax(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return 0;
        }
        return i.slotMax;
    }

    public final double getPrice(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return -1.0;
        }
        return i.price;
    }

    public final double getUnitPrice(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return 0.0;
        }
        return i.unitPrice;
    }

    public final int getWholePrice(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return 0;
        }
        return i.wholePrice;
    }

    protected int rand(int min, int max) {
        return Math.abs(Randomizer.rand(min, max));
    }

    public Equip levelUpEquip(Equip equip, Map<String, Integer> sta) {
        Equip nEquip = (Equip) equip.copy();
        // is this all the stats?
        try {
            for (Entry<String, Integer> stat : sta.entrySet()) {
                switch (stat.getKey()) {
                    case "STRMin":
                        nEquip.setStr((short) (nEquip.getStr(0) + rand(stat.getValue(), sta.get("STRMax"))));
                        break;
                    case "DEXMin":
                        nEquip.setDex((short) (nEquip.getDex(0) + rand(stat.getValue(), sta.get("DEXMax"))));
                        break;
                    case "INTMin":
                        nEquip.setInt((short) (nEquip.getInt(0) + rand(stat.getValue(), sta.get("INTMax"))));
                        break;
                    case "LUKMin":
                        nEquip.setLuk((short) (nEquip.getLuk(0) + rand(stat.getValue(), sta.get("LUKMax"))));
                        break;
                    case "PADMin":
                        nEquip.setWatk((short) (nEquip.getWatk(0) + rand(stat.getValue(), sta.get("PADMax"))));
                        break;
                    case "PDDMin":
                        nEquip.setWdef((short) (nEquip.getWdef(0) + rand(stat.getValue(), sta.get("PDDMax"))));
                        break;
                    case "MADMin":
                        nEquip.setMatk((short) (nEquip.getMatk(0) + rand(stat.getValue(), sta.get("MADMax"))));
                        break;
                    case "SpeedMin":
                        nEquip.setSpeed((short) (nEquip.getSpeed(0) + rand(stat.getValue(), sta.get("SpeedMax"))));
                        break;
                    case "JumpMin":
                        nEquip.setJump((short) (nEquip.getJump(0) + rand(stat.getValue(), sta.get("JumpMax"))));
                        break;
                    case "MHPMin":
                        nEquip.setHp((short) (nEquip.getHp(0) + rand(stat.getValue(), sta.get("MHPMax"))));
                        break;
                    case "MMPMin":
                        nEquip.setMp((short) (nEquip.getMp(0) + rand(stat.getValue(), sta.get("MMPMax"))));
                        break;
                    case "MaxHPMin":
                        nEquip.setHp((short) (nEquip.getHp(0) + rand(stat.getValue(), sta.get("MaxHPMax"))));
                        break;
                    case "MaxMPMin":
                        nEquip.setMp((short) (nEquip.getMp(0) + rand(stat.getValue(), sta.get("MaxMPMax"))));
                        break;
                }
            }
        } catch (NullPointerException e) {
        }
        return nEquip;
    }

    public final List<Triple<String, String, String>> getEquipAdditions(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.equipAdditions;
    }

    public final String getEquipAddReqs(final int itemId, final String key, final String sub) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        for (Triple<String, String, String> data : i.equipAdditions) {
            if (data.getLeft().equals("key") && data.getMid().equals("con:" + sub)) {
                return data.getRight();
            }
        }
        return null;
    }

    public final Map<Integer, Map<String, Integer>> getEquipIncrements(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.equipIncs;
    }

    public final List<Integer> getEquipSkills(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.incSkill;
    }

    public final Map<String, Integer> getEquipStats(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.equipStats;
    }

    public final boolean canEquip(final Map<String, Integer> stats, final int itemid, final int level, final int job,
            final int fame, final int str, final int dex, final int luk, final int int_, int supremacy, byte reqLevel,
            boolean ignoreStat) {
        boolean allow;
        // 屬性值判斷
        if (ignoreStat || (str >= (stats.containsKey("reqSTR") ? stats.get("reqSTR") : 0)
                && dex >= (stats.containsKey("reqDEX") ? stats.get("reqDEX") : 0)
                && luk >= (stats.containsKey("reqLUK") ? stats.get("reqLUK") : 0)
                && int_ >= (stats.containsKey("reqINT") ? stats.get("reqINT") : 0))) {
            final Integer fameReq = stats.get("reqPOP");
            allow = fameReq == null || fame >= fameReq;
        } else {
            allow = false;
        }

        // 等級判斷
        if (level + supremacy >= 0xFF) {
            supremacy = 0xFF - level;
        }
        if (allow && (level + supremacy) < (reqLevel != 0 ? reqLevel
                : stats.containsKey("reqLevel") ? stats.get("reqLevel") : 0)) {
            allow = false;
        }

        // 職業判斷
        if (allow) {
            int reqJob = stats.containsKey("reqJob") ? stats.get("reqJob") : 0;
            if (reqJob != 0) {
                int jobBranch = MapleJob.getJobBranch(job);
                if (reqJob == -1) {
                    // 初心者
                    allow = jobBranch == 0;
                } else {
                    switch (jobBranch) {
                        case 0:
                            // 初心者
                            allow = reqJob == -1;
                            break;
                        case 1:
                            // 劍士
                            allow = (reqJob & 0x1) != 0;
                            break;
                        case 2:
                            // 法師
                            allow = (reqJob & 0x2) != 0;
                            break;
                        case 3:
                            // 弓箭手
                            allow = (reqJob & 0x4) != 0;
                            break;
                        case 4:
                            // 盜賊
                            allow = (reqJob & 0x8) != 0;
                            break;
                        case 5:
                            // 海盜
                            allow = (reqJob & 0x10) != 0;
                            break;
                        case 6:
                            // 傑諾
                            allow = (reqJob & 0x8) != 0 || (reqJob & 0x10) != 0;
                            break;
                        default:
                            allow = false;
                            break;
                    }
                }
            }
        }

        return MapleJob.is管理員(job) || allow;
    }

    public final int getReqLevel(final int itemId) {
        if (getEquipStats(itemId) == null || !getEquipStats(itemId).containsKey("reqLevel")) {
            return 0;
        }
        return getEquipStats(itemId).get("reqLevel");
    }

    public final int getReqJob(final int itemId) {
        if (getEquipStats(itemId) == null || !getEquipStats(itemId).containsKey("reqJob")) {
            return 0;
        }
        return getEquipStats(itemId).get("reqJob");
    }

    public final int getSlots(final int itemId) {
        if (getEquipStats(itemId) == null || !getEquipStats(itemId).containsKey("tuc")) {
            return 0;
        }
        return getEquipStats(itemId).get("tuc");
    }

    public final Integer getSetItemID(final int itemId) {
        if (getEquipStats(itemId) == null || !getEquipStats(itemId).containsKey("setItemID")) {
            return 0;
        }
        return getEquipStats(itemId).get("setItemID");
    }

    public final StructSetItem getSetItem(final int setItemId) {
        return setItems.get(setItemId);
    }

    public final List<Integer> getScrollReqs(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.scrollReqs;
    }

    public int getScrollSuccess(int itemId) {
        if (itemId / 10000 != 204 || getEquipStats(itemId) == null || !getEquipStats(itemId).containsKey("success")) {
            return 0;
        }
        return (getEquipStats(itemId).get("success"));
    }

    public final Item scrollEquipWithId(final Item equip, final Item scroll, final boolean ws, final MapleCharacter chr,
            final int vegas) {
        if (equip.getType() == 1) { // See Item.java
            final Equip nEquip = (Equip) equip;
            final Map<String, Integer> stats = getEquipStats(scroll.getItemId());
            final Map<String, Integer> eqstats = getEquipStats(equip.getItemId());

            // 判斷捲軸成功率與爆率
            int success;
            int curse;
            boolean noCursed = isNoCursedScroll(scroll.getItemId());
            boolean equipScrollSuccess = EquipStat.EnhanctBuff.SCROLL_SUCCESS.check(nEquip.getEnhanctBuff());

            String scrollType;
            int craft = chr.getTrait(MapleTraitType.craft).getLevel() / 10;
            int lucksKey = ItemFlag.LUCKY_DAY.check(equip.getFlag()) ? 10 : 0;
            if (ItemFlag.LUCKY_DAY.check(equip.getFlag()) && !ItemConstants.類型.潛能卷軸(scroll.getItemId())
                    && !ItemConstants.類型.附加潛能卷軸(scroll.getItemId()) && !ItemConstants.類型.裝備強化卷軸(scroll.getItemId())
                    && (!ItemConstants.類型.特殊卷軸(scroll.getItemId())) && !ItemConstants.類型.回真卷軸(scroll.getItemId())) {
                equip.setFlag(equip.getFlag() - ItemFlag.LUCKY_DAY.getValue());
            }
            final int added = lucksKey + craft;

            if (ItemConstants.類型.潛能卷軸(scroll.getItemId())) {
                success = 0;
                curse = 100;
                switch (scroll.getItemId() / 100) {
                    case 20494:
                        scrollType = "普通";
                        curse = noCursed ? 0 : 100;
                        switch (scroll.getItemId()) {// 普通卷轴成功率判断
                            case 2049402:// 超級潛在能力賦予卷軸
                            case 2049404:// [6週年]潛在能力賦予卷軸
                            case 2049405:// 真. 楓葉之心項鍊專用潛在能力卷軸
                            case 2049406:// 特殊賦予潛在能力卷軸
                            case 2049414:// 紫色靈魂戒指專用潛在能力賦予卷軸
                            case 2049415:// 藍色靈魂戒指專用潛在能力賦予卷軸
                            case 2049417:// 特殊潛在能力賦予 卷軸
                            case 2049419:// 特別潛在能力賦予捲軸
                            case 2049423:// 恰吉面具潛能賦予卷軸
                                success = 100;
                                break;
                            case 2049400:// 高級潛在能力賦予卷軸
                            case 2049407:// 高級潛在能力賦予卷軸
                            case 2049412:// 高級潛在能力賦予卷軸
                                success = 90;
                                break;
                            case 2049421:// 心之項鍊潛在能力賦予卷軸
                            case 2049424:// 賦予卡爾頓的鬍子潛在能力卷軸
                                success = 80;
                                break;
                            case 2049401:// 潛在能力賦予卷軸
                            case 2049408:// 潛在能力賦予卷軸
                            case 2049416:// 潛在能力賦予卷軸
                                success = 70;
                                break;
                            default:
                                if (scroll.getItemId() >= 2049427 && scroll.getItemId() <= 2049446) {// 賞金獵人
                                    success = 100;
                                }
                        }
                        switch (scroll.getItemId()) {// 專用捲軸失敗不爆物判斷
                            case 2049404:// [6週年]潛在能力賦予卷軸
                            case 2049405:// 真. 楓葉之心項鍊專用潛在能力卷軸
                            case 2049414:// 紫色靈魂戒指專用潛在能力賦予卷軸
                            case 2049415:// 藍色靈魂戒指專用潛在能力賦予卷軸
                            case 2049421:// 心之項鍊潛在能力賦予卷軸
                            case 2049423:// 恰吉面具潛能賦予卷軸
                            case 2049424:// 賦予卡爾頓的鬍子潛在能力卷軸
                            case 2049426:// 10週年武器專用潛在能力附加卷軸
                            case 2049427:// 賞金獵人
                                scrollType = "專用";
                                curse = 0;
                            default:
                                if (scroll.getItemId() >= 2049427 && scroll.getItemId() <= 2049446) {// 賞金獵人
                                    scrollType = "專用";
                                    curse = 0;
                                }
                        }
                        break;
                    case 20497:
                        success = stats == null || !stats.containsKey("success") ? 0 : stats.get("success");
                        curse = noCursed ? 0 : stats == null || !stats.containsKey("cursed") ? 0 : stats.get("cursed");
                        if (scroll.getItemId() >= 2049700 && scroll.getItemId() < 2049750
                                && scroll.getItemId() != 2049741) {
                            scrollType = "稀有";
                        } else if (scroll.getItemId() >= 2049750 && scroll.getItemId() < 2049759
                                || scroll.getItemId() == 2049741) {
                            scrollType = "罕見";
                        } else if (scroll.getItemId() == 2049780 || scroll.getItemId() == 2049782) {
                            scrollType = "傳說";
                        } else {
                            success = 0;
                            scrollType = "未知";
                        }
                        break;
                    default:
                        scrollType = "未知";
                        break;
                }
                scrollType += "潛能附加捲軸";
            } else if (ItemConstants.類型.附加潛能卷軸(scroll.getItemId())) {
                scrollType = "附加潛在能力附加捲軸";
                success = ItemConstants.卷軸.getBonusPotentialScrollSucc(scroll.getItemId());
                curse = ItemConstants.卷軸.getBonusPotentialScrollCurse(scroll.getItemId());
            } else if (ItemConstants.類型.裝備強化卷軸(scroll.getItemId())) {
                success = stats == null || !stats.containsKey("success") ? 0 : stats.get("success");
                curse = noCursed ? 0 : stats == null || !stats.containsKey("cursed") ? 100 : stats.get("cursed");
                scrollType = "強化捲軸";
                if (getForceUpgrade(scroll.getItemId()) == 1 && success == 0) {
                    success = Math.max((scroll.getItemId() == 2049301 || scroll.getItemId() == 2049307 ? 80 : 100)
                            - nEquip.getEnhance() * 10, 5);
                }
            } else if (ItemConstants.類型.回真卷軸(scroll.getItemId())) {
                success = stats == null || !stats.containsKey("success") ? 0 : stats.get("success");
                curse = noCursed || stats == null || !stats.containsKey("cursed") ? 0 : stats.get("cursed");
                scrollType = "回真捲軸";
            } else {
                if (ItemConstants.類型.幸運日卷軸(scroll.getItemId())) {
                    if (ItemConstants.類型.保護卷軸(scroll.getItemId())) {
                        scrollType = "幸運日+防爆捲軸";
                    } else {
                        scrollType = "幸運日捲軸";
                    }
                } else if (ItemConstants.類型.保護卷軸(scroll.getItemId())) {
                    scrollType = "防爆捲軸";
                } else if (ItemConstants.類型.安全卷軸(scroll.getItemId())) {
                    scrollType = "安全捲軸";
                } else if (ItemConstants.類型.卷軸保護卡(scroll.getItemId())) {
                    scrollType = "捲軸保護卡";
                } else if (ItemConstants.類型.白醫卷軸(scroll.getItemId())) {
                    scrollType = "白醫捲軸";
                } else if (ItemConstants.類型.混沌卷軸(scroll.getItemId())) {
                    scrollType = "混沌捲軸";
                } else {
                    scrollType = "普通捲軸";
                }
                success = stats == null || !stats.containsKey("success") ? ItemConstants.類型.提升卷(scroll.getItemId())
                        ? ItemConstants.卷軸.getSuccessTablet(scroll.getItemId(), nEquip.getLevel())
                        : 0 : stats.get("success");
                curse = stats == null || !stats.containsKey("cursed") ? ItemConstants.類型.提升卷(scroll.getItemId())
                        ? ItemConstants.卷軸.getCurseTablet(scroll.getItemId(), nEquip.getLevel())
                        : 0 : stats.get("cursed");
                success = (success
                        + (vegas == 5610000 && success == 10 ? 20 : (vegas == 5610001 && success == 60 ? 30 : 0)))
                        * (100 + added) / 100;
            }
            if (ItemConstants.類型.安全卷軸(scroll.getItemId()) && isCash(scroll.getItemId())) {
                success = 100;
            }

            if (chr.isShowInfo()) {
                chr.showMessage(8,
                        scrollType + " - 默認幾率：" + success + "% 性向加成：" + craft + "% 幸運日加成：" + lucksKey
                        + "% 裝備自帶卷軸100%成功率：" + equipScrollSuccess + " 最終概率："
                        + (equipScrollSuccess ? 100 : success) + "% 失敗消失概率：" + curse + "%");
            }

            if (success <= 0) {
                chr.dropMessage(1, "捲軸：" + scroll + " 成功幾率為：" + success + " 這個捲軸可能還未修復。");
                chr.getClient().getSession().writeAndFlush(CWvsContext.enableActions());
                return nEquip;
            }

            if (chr.isAdmin() && chr.isInvincible()) {
                success = 100;
                chr.dropMessage(-6, "伺服器管理員無敵狀態成功率100%");
            }

            if (equipScrollSuccess) {
                success = 100;
            }

            if (ItemConstants.類型.潛能卷軸(scroll.getItemId()) || ItemConstants.類型.附加潛能卷軸(scroll.getItemId())
                    || ItemConstants.類型.裝備強化卷軸(scroll.getItemId()) || ItemConstants.類型.特殊卷軸(scroll.getItemId())
                    || ItemConstants.類型.回真卷軸(scroll.getItemId()) || Randomizer.nextInt(100) <= success) {
                if (ItemConstants.類型.幸運日卷軸(scroll.getItemId()) || ItemConstants.類型.保護卷軸(scroll.getItemId())) {
                    if (ItemConstants.類型.幸運日卷軸(scroll.getItemId())) {// 幸運日
                        int flag = nEquip.getFlag();
                        flag = flag | ItemFlag.LUCKY_DAY.getValue();
                        nEquip.setFlag(flag);
                    }
                    if (ItemConstants.類型.保護卷軸(scroll.getItemId())) {// 保護捲軸
                        int flag = nEquip.getFlag();
                        flag = flag | ItemFlag.SHIELD_WARD.getValue();
                        nEquip.setFlag(flag);
                    }
                } else if (ItemConstants.類型.安全卷軸(scroll.getItemId())) {// 安全捲軸
                    int flag = nEquip.getFlag();
                    flag = flag | ItemFlag.SLOTS_PROTECT.getValue();
                    nEquip.setFlag(flag);
                } else if (ItemConstants.類型.卷軸保護卡(scroll.getItemId())) {// 捲軸保護卡
                    int flag = nEquip.getFlag();
                    flag = flag | ItemFlag.SCROLL_PROTECT.getValue();
                    nEquip.setFlag(flag);
                } else if (ItemConstants.類型.裝備強化卷軸(scroll.getItemId())) {// 強化捲軸
                    if (Randomizer.nextInt(100) > success) {
                        if (Randomizer.nextInt(100) < curse) {
                            return null; // destroyed, nib
                        } else {
                            return nEquip;
                        }
                    }
                    for (int i = 0; i < Math.min(getForceUpgrade(scroll.getItemId()),
                            ItemConstants.卷軸.getEnhanceTimes(nEquip.getItemId()) - nEquip.getEnhance()); i++) {
                        EquipmentEnchant.StarForceEnhanceItem(nEquip, false);
                    }
                } else if (ItemConstants.類型.潛能卷軸(scroll.getItemId())) {// 潛能捲軸
                    if (Randomizer.nextInt(100) > success) {
                        return Randomizer.nextInt(99) < curse ? null : nEquip;
                    }
                    if (scroll.getItemId() >= 2049700 && scroll.getItemId() < 2049750
                            && scroll.getItemId() != 2049741) {// 稀有潛能捲軸
                        nEquip.resetPotential(2, false);
                    } else if (scroll.getItemId() >= 2049750 && scroll.getItemId() < 2049759
                            || scroll.getItemId() == 2049741) {// 罕見潛能捲軸
                        nEquip.resetPotential(3, false);
                    } else if (scroll.getItemId() == 2049780 || scroll.getItemId() == 2049782) {// 傳說潛能捲軸
                        nEquip.resetPotential(4, false);
                    } else if (scroll.getItemId() == 2049419) {// 附加3條潛能
                        nEquip.resetPotential(true, false);
                    } else {
                        nEquip.resetPotential(false, false);
                    }
                } else if (ItemConstants.類型.附加潛能卷軸(scroll.getItemId())) {// 附加潛能捲軸
                    if (Randomizer.nextInt(100) > success) {
                        return Randomizer.nextInt(99) < curse ? null : nEquip;
                    }
                    if (scroll.getItemId() == 2048306) {// 3行附加潛能捲軸
                        nEquip.resetPotential(true, true);
                    } else {
                        nEquip.resetPotential(true);
                    }
                } else if (ItemConstants.類型.回真卷軸(scroll.getItemId())) {// 回真捲軸
                    if (equip.getType() != 1) {
                        return equip;
                    }
                    if (Randomizer.nextInt(100) > success) {
                        return Randomizer.nextInt(99) < curse ? null : nEquip;
                    }
                    return resetEquipStats(nEquip,
                            stats != null && stats.containsKey("perfectReset") && stats.get("perfectReset") == 1);
                } else if (scroll.getItemId() == 2040727) { // Spikes on shoe, prevents slip鞋子防滑卷軸10%
                    int flag = nEquip.getFlag();
                    flag |= ItemFlag.SPIKES.getValue();
                    nEquip.setFlag(flag);
                } else if (scroll.getItemId() == 2041058) { // Cape for Cold protection披風防寒卷軸10%
                    int flag = nEquip.getFlag();
                    flag |= ItemFlag.COLD.getValue();
                    nEquip.setFlag(flag);
                } else if (ItemConstants.類型.白醫卷軸(scroll.getItemId())) {// 白醫捲軸
                    nEquip.setUpgradeSlots(
                            (byte) (nEquip.getUpgradeSlots() + (stats == null ? 0 : stats.get("recover"))));
                } else if (ItemConstants.類型.混沌卷軸(scroll.getItemId())) {// 混沌捲軸
                    final int z = ItemConstants.卷軸.getChaosNumber(scroll.getItemId());
                    int increase = Randomizer.nextBoolean() ? 1
                            : (ItemConstants.類型.樂觀混沌卷軸(scroll.getItemId())) || (isNegativeScroll(scroll.getItemId()))
                            ? 1
                            : -1;
                    if (nEquip.getStr(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setStr((short) (nEquip.getStr(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getDex(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setDex((short) (nEquip.getDex(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getInt(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setInt((short) (nEquip.getInt(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getLuk(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setLuk((short) (nEquip.getLuk(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getWatk(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setWatk((short) (nEquip.getWatk(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getWdef(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setWdef((short) (nEquip.getWdef(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getMatk(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setMatk((short) (nEquip.getMatk(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    // if (nEquip.getMdef(0) > 0 && Randomizer.nextInt(10) > 5) {
                    // nEquip.setMdef((short) (nEquip.getMdef(0) + Randomizer.nextInt(z) *
                    // (Randomizer.nextBoolean() ? 1 : increase)));
                    // }
                    // if (nEquip.getAcc(0) > 0 && Randomizer.nextInt(10) > 5) {
                    // nEquip.setAcc((short) (nEquip.getAcc(0) + Randomizer.nextInt(z) *
                    // (Randomizer.nextBoolean() ? 1 : increase)));
                    // }
                    // if (nEquip.getAvoid(0) > 0 && Randomizer.nextInt(10) > 5) {
                    // nEquip.setAvoid((short) (nEquip.getAvoid(0) + Randomizer.nextInt(z) *
                    // (Randomizer.nextBoolean() ? 1 : increase)));
                    // }
                    if (nEquip.getSpeed(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setSpeed((short) (nEquip.getSpeed(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getJump(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setJump((short) (nEquip.getJump(0)
                                + Randomizer.nextInt(z) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getHp(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setHp((short) (nEquip.getHp(0)
                                + Randomizer.nextInt(z * 10) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                    if (nEquip.getMp(0) > 0 && Randomizer.nextInt(10) > 5) {
                        nEquip.setMp((short) (nEquip.getMp(0)
                                + Randomizer.nextInt(z * 10) * (Randomizer.nextBoolean() ? 1 : increase)));
                    }
                } else {
                    for (Entry<String, Integer> stat : stats.entrySet()) {
                        final String key = stat.getKey();
                        switch (key) {
                            case "STR":
                                nEquip.setStr((short) (nEquip.getStr(0) + stat.getValue()));
                                break;
                            case "DEX":
                                nEquip.setDex((short) (nEquip.getDex(0) + stat.getValue()));
                                break;
                            case "INT":
                                nEquip.setInt((short) (nEquip.getInt(0) + stat.getValue()));
                                break;
                            case "LUK":
                                nEquip.setLuk((short) (nEquip.getLuk(0) + stat.getValue()));
                                break;
                            case "PAD":
                                nEquip.setWatk((short) (nEquip.getWatk(0) + stat.getValue()));
                                break;
                            case "PDD":
                                nEquip.setWdef((short) (nEquip.getWdef(0) + stat.getValue()));
                                break;
                            case "MAD":
                                nEquip.setMatk((short) (nEquip.getMatk(0) + stat.getValue()));
                                break;
                            case "Speed":
                                nEquip.setSpeed((short) (nEquip.getSpeed(0) + stat.getValue()));
                                break;
                            case "Jump":
                                nEquip.setJump((short) (nEquip.getJump(0) + stat.getValue()));
                                break;
                            case "MHP":
                                nEquip.setHp((short) (nEquip.getHp(0) + stat.getValue()));
                                break;
                            case "MMP":
                                nEquip.setMp((short) (nEquip.getMp(0) + stat.getValue()));
                                break;
                        }
                    }
                }
                if (!ItemConstants.類型.白醫卷軸(scroll.getItemId()) && !ItemConstants.類型.特殊卷軸(scroll.getItemId())
                        && !ItemConstants.類型.裝備強化卷軸(scroll.getItemId()) && !ItemConstants.類型.潛能卷軸(scroll.getItemId())
                        && !ItemConstants.類型.附加潛能卷軸(scroll.getItemId()) && 2040727 != scroll.getItemId()
                        && 2041058 != scroll.getItemId()) {
                    if (ItemConstants.類型.阿斯旺卷軸(scroll.getItemId())) {
                        nEquip.setUpgradeSlots((byte) (nEquip.getUpgradeSlots() - eqstats.get("tuc")));
                    } else {
                        nEquip.setUpgradeSlots((byte) (nEquip.getUpgradeSlots() - 1));
                    }
                    nEquip.setLevel((byte) (nEquip.getLevel() + 1));

                    int oldFlag = nEquip.getFlag();
                    if (ItemFlag.SLOTS_PROTECT.check(oldFlag)) {
                        nEquip.setFlag(oldFlag - ItemFlag.SLOTS_PROTECT.getValue());
                    }
                }
            } else {
                if (!ws && !ItemConstants.類型.白醫卷軸(scroll.getItemId()) && 2040727 != scroll.getItemId()
                        && 2041058 != scroll.getItemId()) {
                    int oldFlag = nEquip.getFlag();
                    if (ItemFlag.SLOTS_PROTECT.check(oldFlag)) {
                        nEquip.setFlag(oldFlag - ItemFlag.SLOTS_PROTECT.getValue());
                        chr.showMessage(11, "由卷軸的效果來，不會扣掉可以升級的次數。");
                    } else if (ItemConstants.類型.阿斯旺卷軸(scroll.getItemId())) {
                        nEquip.setUpgradeSlots((byte) (nEquip.getUpgradeSlots() - eqstats.get("tuc")));
                    } else {
                        nEquip.setUpgradeSlots((byte) (nEquip.getUpgradeSlots() - 1));
                    }
                }
                if (Randomizer.nextInt(99) < curse) {
                    return null;
                }
            }
        }
        return equip;
    }

    public final Item getEquipById(final int equipId) {
        return getEquipById(equipId, -1);
    }

    public final Item getEquipById(final int equipId, final int ringId) {
        final ItemInformation i = getItemInformation(equipId);
        if (i == null) {
            return new Equip(equipId, (short) 0, ringId, 0);
        }
        final Item eq = i.eq.copy();
        eq.setUniqueId(ringId);
        return eq;
    }

    protected final short getRandStatFusion(final short defaultValue, final int value1, final int value2) {
        if (defaultValue == 0) {
            return 0;
        }
        final int range = ((value1 + value2) / 2) - defaultValue;
        final int rand = Randomizer.nextInt(Math.abs(range) + 1);
        return (short) (defaultValue + (range < 0 ? -rand : rand));
    }

    protected final short getRandStat(final short defaultValue, final int maxRange) {
        if (defaultValue == 0) {
            return 0;
        }
        // vary no more than ceil of 10% of stat
        final int lMaxRange = (int) Math.min(Math.ceil(defaultValue * 0.1), maxRange);

        return (short) ((defaultValue - lMaxRange) + Randomizer.nextInt(lMaxRange * 2 + 1));
    }

    protected final short getRandStatAbove(final short defaultValue, final int maxRange) {
        if (defaultValue <= 0) {
            return 0;
        }
        final int lMaxRange = (int) Math.min(Math.ceil(defaultValue * 0.1), maxRange);

        return (short) ((defaultValue) + Randomizer.nextInt(lMaxRange + 1));
    }

    public final Equip randomizeStats(final Equip equip) {
        equip.setStr(getRandStat(equip.getStr(0), 5));
        equip.setDex(getRandStat(equip.getDex(0), 5));
        equip.setInt(getRandStat(equip.getInt(0), 5));
        equip.setLuk(getRandStat(equip.getLuk(0), 5));
        equip.setMatk(getRandStat(equip.getMatk(0), 5));
        equip.setWatk(getRandStat(equip.getWatk(0), 5));
        // equip.setAcc(getRandStat(equip.getAcc(0), 5));
        // equip.setAvoid(getRandStat(equip.getAvoid(0), 5));
        equip.setJump(getRandStat(equip.getJump(0), 5));
        equip.setHands(getRandStat(equip.getHands(0), 5));
        equip.setSpeed(getRandStat(equip.getSpeed(0), 5));
        equip.setWdef(getRandStat(equip.getWdef(0), 10));
        // equip.setMdef(getRandStat(equip.getMdef(0), 10));
        equip.setHp(getRandStat(equip.getHp(0), 10));
        equip.setMp(getRandStat(equip.getMp(0), 10));
        return equip;
    }

    public final Equip randomizeStats_Above(final Equip equip) {
        equip.setStr(getRandStatAbove(equip.getStr(0), 5));
        equip.setDex(getRandStatAbove(equip.getDex(0), 5));
        equip.setInt(getRandStatAbove(equip.getInt(0), 5));
        equip.setLuk(getRandStatAbove(equip.getLuk(0), 5));
        equip.setMatk(getRandStatAbove(equip.getMatk(0), 5));
        equip.setWatk(getRandStatAbove(equip.getWatk(0), 5));
        // equip.setAcc(getRandStatAbove(equip.getAcc(0), 5));
        // equip.setAvoid(getRandStatAbove(equip.getAvoid(0), 5));
        equip.setJump(getRandStatAbove(equip.getJump(0), 5));
        equip.setHands(getRandStatAbove(equip.getHands(0), 5));
        equip.setSpeed(getRandStatAbove(equip.getSpeed(0), 5));
        equip.setWdef(getRandStatAbove(equip.getWdef(0), 10));
        // equip.setMdef(getRandStatAbove(equip.getMdef(0), 10));
        equip.setHp(getRandStatAbove(equip.getHp(0), 10));
        equip.setMp(getRandStatAbove(equip.getMp(0), 10));
        return equip;
    }

    public final Equip fuse(final Equip equip1, final Equip equip2) {
        if (equip1.getItemId() != equip2.getItemId()) {
            return equip1;
        }
        final Equip equip = (Equip) getEquipById(equip1.getItemId());
        equip.setStr(getRandStatFusion(equip.getStr(0), equip1.getStr(0), equip2.getStr(0)));
        equip.setDex(getRandStatFusion(equip.getDex(0), equip1.getDex(0), equip2.getDex(0)));
        equip.setInt(getRandStatFusion(equip.getInt(0), equip1.getInt(0), equip2.getInt(0)));
        equip.setLuk(getRandStatFusion(equip.getLuk(0), equip1.getLuk(0), equip2.getLuk(0)));
        equip.setMatk(getRandStatFusion(equip.getMatk(0), equip1.getMatk(0), equip2.getMatk(0)));
        equip.setWatk(getRandStatFusion(equip.getWatk(0), equip1.getWatk(0), equip2.getWatk(0)));
        // equip.setAcc(getRandStatFusion(equip.getAcc(0), equip1.getAcc(0),
        // equip2.getAcc(0)));
        // equip.setAvoid(getRandStatFusion(equip.getAvoid(0), equip1.getAvoid(0),
        // equip2.getAvoid(0)));
        equip.setJump(getRandStatFusion(equip.getJump(0), equip1.getJump(0), equip2.getJump(0)));
        equip.setHands(getRandStatFusion(equip.getHands(0), equip1.getHands(0), equip2.getHands(0)));
        equip.setSpeed(getRandStatFusion(equip.getSpeed(0), equip1.getSpeed(0), equip2.getSpeed(0)));
        equip.setWdef(getRandStatFusion(equip.getWdef(0), equip1.getWdef(0), equip2.getWdef(0)));
        // equip.setMdef(getRandStatFusion(equip.getMdef0(), equip1.getMdef(0),
        // equip2.getMdef(0)));
        equip.setHp(getRandStatFusion(equip.getHp(0), equip1.getHp(0), equip2.getHp(0)));
        equip.setMp(getRandStatFusion(equip.getMp(0), equip1.getMp(0), equip2.getMp(0)));
        return equip;
    }

    public final int getTotalStat(final Equip equip) { // i get COOL when my defense is higher on gms...
        return equip.getStr(0) + equip.getDex(0) + equip.getInt(0) + equip.getLuk(0) + equip.getMatk(0)
                + equip.getWatk(0) + equip.getJump(0) + equip.getHands(0) + equip.getSpeed(0) + equip.getHp(0)
                + equip.getMp(0) + equip.getWdef(0);
    }

    public final MapleStatEffect getItemEffect(final int itemId) {
        MapleStatEffect ret = itemEffects.get(itemId);
        if (ret == null) {
            final MapleData item = getItemData(itemId);
            if (item == null || item.getChildByPath("spec") == null) {
                return null;
            }
            ret = MapleStatEffect.loadItemEffectFromData(item.getChildByPath("spec"), itemId);
            itemEffects.put(itemId, ret);
        }
        return ret;
    }

    public int getNpc(int itemid, int def) {
        Integer npcId = npcs.get(itemid);
        if (npcId == null) {
            final MapleData data = getItemData(itemid);
            if (data != null) {
                npcId = MapleDataTool.getInt("spec/npc", data, def);
                if (npcId != def) {
                    npcs.put(itemid, npcId);
                }
            }
        }
        return def;
    }

    public int getScriptName(int itemid) {
        final MapleData itemData = getItemData(itemid);
        if (itemData != null) {
            return MapleDataTool.getInt("spec/script", itemData, 0);
        }
        return 0;
    }

    public final MapleStatEffect getItemEffectEX(final int itemId) {
        MapleStatEffect ret = itemEffectsEx.get(itemId);
        if (ret == null) {
            final MapleData item = getItemData(itemId);
            if (item == null || item.getChildByPath("specEx") == null) {
                return null;
            }
            ret = MapleStatEffect.loadItemEffectFromData(item.getChildByPath("specEx"), itemId);
            itemEffectsEx.put(itemId, ret);
        }
        return ret;
    }

    public final int getCreateId(final int id) {
        final ItemInformation i = getItemInformation(id);
        if (i == null) {
            return 0;
        }
        return i.create;
    }

    public final int getCardMobId(final int id) {
        final ItemInformation i = getItemInformation(id);
        if (i == null) {
            return 0;
        }
        return i.monsterBook;
    }

    public final int getBagType(final int id) {
        final ItemInformation i = getItemInformation(id);
        if (i == null) {
            return 0;
        }
        return i.flag & 0xF;
    }

    public int[][] getSummonMobs(int itemId) {
        MapleData data = getItemData(itemId);
        int theInt = data.getChildByPath("mob").getChildren().size();
        int[][] mobs2spawn = new int[theInt][2];
        for (int x = 0; x < theInt; x++) {
            mobs2spawn[x][0] = MapleDataTool.getIntConvert("mob/" + x + "/id", data);
            mobs2spawn[x][1] = MapleDataTool.getIntConvert("mob/" + x + "/prob", data);
        }
        return mobs2spawn;
    }

    public final int getWatkForProjectile(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null || i.equipStats == null || i.equipStats.get("incPAD") == null) {
            return 0;
        }
        return i.equipStats.get("incPAD");
    }

    public final boolean canScroll(final int scrollid, final int itemid) {
        return (scrollid / 100) % 100 == (itemid / 10000) % 100 || itemid / 1000 == 1672;// 心臟
    }

    public final String getName(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.name;
    }

    public final String getDesc(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.desc;
    }

    public final String getMsg(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.msg;
    }

    public final short getItemMakeLevel(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return 0;
        }
        return i.itemMakeLevel;
    }

    public final boolean isDropRestricted(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x200) != 0 || (i.flag & 0x400) != 0;
    }

    public final boolean isPickupRestricted(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x80) != 0;
    }

    public final boolean isAccountShared(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x100) != 0;
    }

    public final boolean isSharableOnce(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x2000) != 0;
    }

    public final int getStateChangeItem(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return 0;
        }
        return i.stateChange;
    }

    public final int getMeso(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return 0;
        }
        return i.meso;
    }

    public final boolean isShareTagEnabled(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x800) != 0;
    }

    public final boolean isKarmaEnabled(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return i.karmaEnabled == 1;
    }

    public final boolean isPKarmaEnabled(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return i.karmaEnabled == 2;
    }

    public final boolean isPickupBlocked(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x40) != 0;
    }

    public final boolean isLogoutExpire(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x20) != 0;
    }

    public Map<Integer, List<StructItemOption>> getFamiliar_option() {
        return this.familiar_option;
    }

    public Map<Integer, Map<Integer, Float>> getFamiliarTable_pad() {
        return this.familiarTable_pad;
    }

    public List<Integer> getRandomFamiliarCard(int count) {
        List<Integer> ret = new ArrayList<>();
        List<Integer> all = new ArrayList<>(this.familiars.keySet());
        while (ret.size() < count) {
            Collections.shuffle(all);
            ret.add(this.familiars.get(all.get(Randomizer.nextInt(all.size()))).getMonsterCardId());
        }
        return ret;
    }

    public final boolean cantSell(final int itemId) { // true = cant sell, false = can sell
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x10) != 0;
    }

    public final Pair<Integer, List<StructRewardItem>> getRewardItem(final int itemid) {
        final ItemInformation i = getItemInformation(itemid);
        if (i == null) {
            return null;
        }
        return new Pair<>(i.totalprob, i.rewardItems);
    }

    public final boolean isMobHP(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x1000) != 0;
    }

    public final boolean isQuestItem(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return false;
        }
        return (i.flag & 0x200) != 0;
    }

    public final Pair<Integer, List<Integer>> questItemInfo(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return new Pair<>(i.questId, i.questItems);
    }

    public final Pair<Integer, String> replaceItemInfo(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return new Pair<>(i.replaceItem, i.replaceMsg);
    }

    public final List<Triple<String, Point, Point>> getAfterImage(final String after) {
        return afterImage.get(after);
    }

    public final String getAfterImage(final int itemId) {
        final ItemInformation i = getItemInformation(itemId);
        if (i == null) {
            return null;
        }
        return i.afterImage;
    }

    public final boolean itemExists(final int itemId) {
        if (GameConstants.getInventoryType(itemId) == MapleInventoryType.UNDEFINED) {
            return false;
        }
        return getItemInformation(itemId) != null;
    }

    public final boolean isCash(final int itemId) {
        if (getEquipStats(itemId) == null) {
            return GameConstants.getInventoryType(itemId) == MapleInventoryType.CASH;
        }
        return GameConstants.getInventoryType(itemId) == MapleInventoryType.CASH
                || getEquipStats(itemId).get("cash") != null;
    }

    public final ItemInformation getItemInformation(final int itemId) {
        if (itemId <= 0) {
            return null;
        }
        return dataCache.get(itemId);
    }

    private ItemInformation tmpInfo = null;

    public void initItemRewardData(ResultSet sqlRewardData) throws SQLException {
        final int itemID = sqlRewardData.getInt("itemid");
        if (tmpInfo == null || tmpInfo.itemId != itemID) {
            if (!dataCache.containsKey(itemID)) {
                System.out.println(
                        "[initItemRewardData] Tried to load an item while this is not in the cache: " + itemID);
                return;
            }
            tmpInfo = dataCache.get(itemID);
        }

        if (tmpInfo.rewardItems == null) {
            tmpInfo.rewardItems = new ArrayList<>();
        }

        StructRewardItem add = new StructRewardItem();
        add.itemid = sqlRewardData.getInt("item");
        add.period = (GameConstants.Equipment_Bonus_EXP(add.itemid) > 0 ? Math.max(sqlRewardData.getInt("period"), 7200)
                : sqlRewardData.getInt("period"));
        add.prob = sqlRewardData.getInt("prob");
        add.quantity = sqlRewardData.getShort("quantity");
        add.worldmsg = sqlRewardData.getString("worldMsg").length() <= 0 ? null : sqlRewardData.getString("worldMsg");
        add.effect = sqlRewardData.getString("effect");

        tmpInfo.rewardItems.add(add);
    }

    public void runFamiliar() {
        MapleDataDirectoryEntry familiarData = (MapleDataDirectoryEntry) chrData.getRoot().getEntry("Familiar");
        MapleData consu0287 = this.itemData.getData("Consume/0287.img");
        for (MapleDataFileEntry sub : familiarData.getFiles()) {
            int grade = Integer.parseInt(sub.getName().substring(0, sub.getName().length() - 4));
            MapleData data = chrData.getData("Familiar/" + sub.getName());
            int skillId = MapleDataTool.getInt("info/skill/id", data, 0);
            int effectAfter = MapleDataTool.getInt("info/skill/effectAfter", data, 0);
            int mobId = MapleDataTool.getInt("info/MobID", data, 0);
            int monsterCardID = MapleDataTool.getInt("info/monsterCardID", data, 0);
            this.familiars.put(grade, new StructFamiliar(grade, skillId, effectAfter, mobId, monsterCardID));
            this.familiars_Item.put(monsterCardID,
                    new StructFamiliar(grade, skillId, effectAfter, mobId, monsterCardID));
            this.familiars_Mob.put(mobId, new StructFamiliar(grade, skillId, effectAfter, mobId, monsterCardID));
        }
        MapleData familiarTable = etcData.getData("FamiliarTable.img");
        for (MapleData prop : familiarTable.getChildren()) {
            if (prop.getName().equals("stat")) {
                for (MapleData stat : prop.getChildren()) {
                    Map<Integer, Float> psds = new HashMap<>();
                    for (MapleData level : stat.getChildren()) {
                        psds.put(Integer.parseInt(level.getName()), MapleDataTool.getFloat("pad", level, 0.0f));
                    }
                    this.familiarTable_pad.put(Integer.parseInt(stat.getName()), psds);
                }
            } else if (prop.getName().equals("reinforce_chance")) {
                for (MapleData stat : prop.getChildren()) {
                    Map<Integer, Integer> data = new HashMap<>();
                    for (MapleData level : stat.getChildren()) {
                        data.put(Integer.parseInt(level.getName()), MapleDataTool.getInt(level, 0));
                    }
                    this.familiarTable_rchance.put(Integer.parseInt(stat.getName()), data);
                }
            } else if (prop.getName().equals("fee")) {
                for (MapleData level : prop.getChildByPath("reinforce").getChildren()) {
                    this.familiarTable_fee_reinforce.put(Integer.parseInt(level.getName()),
                            MapleDataTool.getInt(level, 0));
                }
                for (MapleData level : prop.getChildByPath("evolve").getChildren()) {
                    this.familiarTable_fee_evolve.put(Integer.parseInt(level.getName()),
                            MapleDataTool.getInt(level, 0));
                }
            }
        }
        MapleData familiarSet = this.etcData.getData("FamiliarSet.img");
        for (MapleData sub : familiarSet.getChildren()) {
            String id = sub.getName();
            SetItem fset = new SetItem();
            List<Integer> familiarList = new ArrayList<>();
            MapleData statsData = sub.getChildByPath("stats");
            fset.incSpeed = MapleDataTool.getInt("incSpeed", statsData, 0);
            fset.incJump = MapleDataTool.getInt("incJump", statsData, 0);
            fset.incMHP = MapleDataTool.getInt("incMHP", statsData, 0);
            fset.incMMP = MapleDataTool.getInt("incMMP", statsData, 0);
            fset.incSTR = MapleDataTool.getInt("incSTR", statsData, 0);
            fset.incDEX = MapleDataTool.getInt("incDEX", statsData, 0);
            fset.incINT = MapleDataTool.getInt("incINT", statsData, 0);
            fset.incLUK = MapleDataTool.getInt("incLUK", statsData, 0);
            fset.incDEX = MapleDataTool.getInt("incDEX", statsData, 0);
            fset.incDEX = MapleDataTool.getInt("incDEX", statsData, 0);
            fset.incPAD = MapleDataTool.getInt("incPAD", statsData, 0);
            fset.incMAD = MapleDataTool.getInt("incMAD", statsData, 0);
            fset.incAllStat = MapleDataTool.getInt("incAllStat", statsData, 0);
            for (MapleData familiar : sub.getChildByPath("familiarList").getChildren()) {
                familiarList.add(MapleDataTool.getInt(familiar, 0));
            }
            this.familiarSets.put(Integer.parseInt(id), new Triple<>(sub.getName(), familiarList, fset));
        }

        MapleData familiarOpt = this.itemData.getData("FamiliarOption.img");
        for (MapleData option : familiarOpt.getChildren()) {
            int opId = Integer.parseInt(option.getName());
            List<StructItemOption> levels = new LinkedList<>();
            for (MapleData levelData : option.getChildByPath("level").getChildren()) {
                StructItemOption opt = new StructItemOption();
                opt.opID = opId;
                opt.optionType = MapleDataTool.getInt("info/optionType", option, 0);
                opt.reqLevel = MapleDataTool.getInt("info/reqLevel", option, 0);
                opt.string = MapleDataTool.getString("info/string", option, "");
                final int level = MapleDataTool.getIntConvert(levelData, 0);
                for (final String type : StructItemOption.types) {
                    final int value = MapleDataTool.getIntConvert(type, levelData, 0);
                    if (level > 0) { // Save memory
                        opt.data.put(type, value);
                    }
                }
                levels.add(opt);
            }
            this.familiar_option.put(opId, levels);
        }
    }

    public void initItemAddData(ResultSet sqlAddData) throws SQLException {
        final int itemID = sqlAddData.getInt("itemid");
        if (tmpInfo == null || tmpInfo.itemId != itemID) {
            if (!dataCache.containsKey(itemID)) {
                System.out.println("[initItemAddData] Tried to load an item while this is not in the cache: " + itemID);
                return;
            }
            tmpInfo = dataCache.get(itemID);
        }

        if (tmpInfo.equipAdditions == null) {
            tmpInfo.equipAdditions = new LinkedList<>();
        }

        while (sqlAddData.next()) {
            tmpInfo.equipAdditions.add(new Triple<>(sqlAddData.getString("key"), sqlAddData.getString("subKey"),
                    sqlAddData.getString("value")));
        }
    }

    public void initItemEquipData(ResultSet sqlEquipData) throws SQLException {
        final int itemID = sqlEquipData.getInt("itemid");
        if (tmpInfo == null || tmpInfo.itemId != itemID) {
            if (!dataCache.containsKey(itemID)) {
                System.out
                        .println("[initItemEquipData] Tried to load an item while this is not in the cache: " + itemID);
                return;
            }
            tmpInfo = dataCache.get(itemID);
        }

        if (tmpInfo.equipStats == null) {
            tmpInfo.equipStats = new HashMap<>();
        }

        final int itemLevel = sqlEquipData.getInt("itemLevel");
        if (itemLevel == -1) {
            tmpInfo.equipStats.put(sqlEquipData.getString("key"), sqlEquipData.getInt("value"));
        } else {
            if (tmpInfo.equipIncs == null) {
                tmpInfo.equipIncs = new HashMap<>();
            }

            Map<String, Integer> toAdd = tmpInfo.equipIncs.get(itemLevel);
            if (toAdd == null) {
                toAdd = new HashMap<>();
                tmpInfo.equipIncs.put(itemLevel, toAdd);
            }
            toAdd.put(sqlEquipData.getString("key"), sqlEquipData.getInt("value"));
        }
    }

    public void finalizeEquipData(ItemInformation item) {
        int itemId = item.itemId;

        // Some equips do not have equip data. So we initialize it anyway if not
        // initialized
        // already
        // Credits: Jay :)
        if (item.equipStats == null) {
            item.equipStats = new HashMap<>();
        }

        item.eq = new Equip(itemId, (byte) 0, -1, 0);
        short stats = GameConstants.getStat(itemId, 0);
        if (stats > 0) {
            item.eq.setStr(stats);
            item.eq.setDex(stats);
            item.eq.setInt(stats);
            item.eq.setLuk(stats);
        }
        stats = GameConstants.getATK(itemId, 0);
        if (stats > 0) {
            item.eq.setWatk(stats);
            item.eq.setMatk(stats);
        }
        stats = GameConstants.getHpMp(itemId, 0);
        if (stats > 0) {
            item.eq.setHp(stats);
            item.eq.setMp(stats);
        }
        stats = GameConstants.getDEF(itemId, 0);
        if (stats > 0) {
            item.eq.setWdef(stats);
            item.eq.setMdef(stats);
        }
        // 讀取裝備屬性(新增屬性在這裡)
        if (item.equipStats.size() > 0) {
            for (Entry<String, Integer> stat : item.equipStats.entrySet()) {
                final String key = stat.getKey();
                switch (key) {
                    case "STR":
                        item.eq.setStr(GameConstants.getStat(itemId, stat.getValue()));
                        break;
                    case "DEX":
                        item.eq.setDex(GameConstants.getStat(itemId, stat.getValue()));
                        break;
                    case "INT":
                        item.eq.setInt(GameConstants.getStat(itemId, stat.getValue()));
                        break;
                    case "LUK":
                        item.eq.setLuk(GameConstants.getStat(itemId, stat.getValue()));
                        break;
                    case "PAD":
                        item.eq.setWatk(GameConstants.getATK(itemId, stat.getValue()));
                        break;
                    case "PDD":
                        item.eq.setWdef(GameConstants.getDEF(itemId, stat.getValue()));
                        break;
                    case "MAD":
                        item.eq.setMatk(GameConstants.getATK(itemId, stat.getValue()));
                        break;
                    case "MDD":
                        item.eq.setMdef(GameConstants.getDEF(itemId, stat.getValue()));
                        break;
                    case "ACC":
                        item.eq.setAcc((short) (int) stat.getValue());
                        break;
                    case "EVA":
                        item.eq.setAvoid((short) (int) stat.getValue());
                        break;
                    case "Speed":
                        item.eq.setSpeed((short) (int) stat.getValue());
                        break;
                    case "Jump":
                        item.eq.setJump((short) (int) stat.getValue());
                        break;
                    case "MHP":
                        item.eq.setHp(GameConstants.getHpMp(itemId, stat.getValue()));
                        break;
                    case "MMP":
                        item.eq.setMp(GameConstants.getHpMp(itemId, stat.getValue()));
                        break;
                    case "tuc":
                        item.eq.setUpgradeSlots(stat.getValue().byteValue());
                        break;
                    case "Craft":
                        item.eq.setHands(stat.getValue().shortValue());
                        break;
                    case "durability":
                        item.eq.setDurability(stat.getValue());
                        break;
                    case "charmEXP":
                        item.eq.setCharmEXP(stat.getValue().shortValue());
                        break;
                    case "PVPDamage":
                        item.eq.setPVPDamage(stat.getValue().shortValue());
                        break;
                    case "bdR":
                        item.eq.setBossDamage(stat.getValue().byteValue());
                        break;
                    case "imdR":
                        item.eq.setIgnorePDR(stat.getValue().byteValue());
                        break;
                }
            }
            if (item.equipStats.get("cash") != null && item.eq.getCharmEXP() <= 0) { // set the exp
                short exp = 0;
                int identifier = itemId / 10000;
                if (ItemConstants.類型.武器(itemId) || identifier == 106) { // weapon overall
                    exp = 60;
                } else if (identifier == 100) { // hats
                    exp = 50;
                } else if (ItemConstants.類型.飾品(itemId) || identifier == 102 || identifier == 108 || identifier == 107) { // gloves
                    // shoes
                    // accessory
                    exp = 40;
                } else if (identifier == 104 || identifier == 105 || identifier == 110) { // top bottom cape
                    exp = 30;
                }
                item.eq.setCharmEXP(exp);
            }
        }
    }

    public void initItemInformation(ResultSet sqlItemData) throws SQLException {
        final ItemInformation ret = new ItemInformation();
        final int itemId = sqlItemData.getInt("itemid");
        ret.itemId = itemId;
        ret.slotMax = sqlItemData.getShort("slotMax");
        ret.price = Double.parseDouble(sqlItemData.getString("price"));
        ret.unitPrice = Double.parseDouble(sqlItemData.getString("unitPrice"));
        ret.wholePrice = sqlItemData.getInt("wholePrice");
        ret.stateChange = sqlItemData.getInt("stateChange");
        if (ItemConstants.類型.裝備(itemId) && !ItemConstants.類型.能源(itemId) && ItemConstants.類型.getGeder(itemId) < 2) {
            ret.name = sqlItemData.getString("name") + "（" + (ItemConstants.類型.getGeder(itemId) == 0 ? "男" : "女") + "）";
        } else {
            ret.name = sqlItemData.getString("name");
        }
        ret.desc = sqlItemData.getString("desc");
        ret.msg = sqlItemData.getString("msg");

        ret.flag = sqlItemData.getInt("flags");

        ret.karmaEnabled = sqlItemData.getByte("karma");
        ret.meso = sqlItemData.getInt("meso");
        ret.monsterBook = sqlItemData.getInt("monsterBook");
        ret.itemMakeLevel = sqlItemData.getShort("itemMakeLevel");
        ret.questId = sqlItemData.getInt("questId");
        ret.create = sqlItemData.getInt("create");
        ret.replaceItem = sqlItemData.getInt("replaceId");
        ret.replaceMsg = sqlItemData.getString("replaceMsg");
        ret.afterImage = sqlItemData.getString("afterImage");
        ret.cardSet = 0;
        if (ret.monsterBook > 0 && itemId / 10000 == 238) {
            mobIds.put(ret.monsterBook, itemId);
            for (Entry<Integer, Triple<Integer, List<Integer>, List<Integer>>> set : monsterBookSets.entrySet()) {
                if (set.getValue().mid.contains(itemId)) {
                    ret.cardSet = set.getKey();
                    break;
                }
            }
        }

        final String scrollRq = sqlItemData.getString("scrollReqs");
        if (scrollRq.length() > 0) {
            ret.scrollReqs = new ArrayList<>();
            final String[] scroll = scrollRq.split(",");
            for (String s : scroll) {
                if (s.length() > 1) {
                    ret.scrollReqs.add(Integer.parseInt(s));
                }
            }
        }
        final String consumeItem = sqlItemData.getString("consumeItem");
        if (consumeItem.length() > 0) {
            ret.questItems = new ArrayList<>();
            final String[] scroll = scrollRq.split(",");
            for (String s : scroll) {
                if (s.length() > 1) {
                    ret.questItems.add(Integer.parseInt(s));
                }
            }
        }

        ret.totalprob = sqlItemData.getInt("totalprob");
        final String incRq = sqlItemData.getString("incSkill");
        if (incRq.length() > 0) {
            ret.incSkill = new ArrayList<>();
            final String[] scroll = incRq.split(",");
            for (String s : scroll) {
                if (s.length() > 1) {
                    ret.incSkill.add(Integer.parseInt(s));
                }
            }
        }
        dataCache.put(itemId, ret);
    }

    public boolean isEquip(int itemId) {
        return itemId / 1000000 == 1;
    }

    public final FarmItemInformation getFarmItemInformation(final int itemId) {
        if (itemId <= 0) {
            return null;
        }
        return farmDataCache.get(itemId);
    }

    public void initFarmItemInformation(ResultSet sqlItemData) throws SQLException {
        final FarmItemInformation ret = new FarmItemInformation();
        final int itemId = sqlItemData.getInt("itemid");
        ret.itemId = itemId;
        ret.slotMax = sqlItemData.getShort("slotMax");
        ret.price = Double.parseDouble(sqlItemData.getString("price"));
        ret.unitPrice = Double.parseDouble(sqlItemData.getString("unitPrice"));
        ret.wholePrice = sqlItemData.getInt("wholePrice");
        ret.stateChange = sqlItemData.getInt("stateChange");
        ret.name = sqlItemData.getString("name");
        ret.desc = sqlItemData.getString("desc");
        ret.msg = sqlItemData.getString("msg");

        ret.flag = sqlItemData.getInt("flags");

        ret.karmaEnabled = sqlItemData.getByte("karma");
        ret.meso = sqlItemData.getInt("meso");
        ret.monsterBook = sqlItemData.getInt("monsterBook");
        ret.itemMakeLevel = sqlItemData.getShort("itemMakeLevel");
        ret.questId = sqlItemData.getInt("questId");
        ret.create = sqlItemData.getInt("create");
        ret.replaceItem = sqlItemData.getInt("replaceId");
        ret.replaceMsg = sqlItemData.getString("replaceMsg");
        ret.afterImage = sqlItemData.getString("afterImage");
        ret.cardSet = 0;
        if (ret.monsterBook > 0 && itemId / 10000 == 238) {
            mobIds.put(ret.monsterBook, itemId);
            for (Entry<Integer, Triple<Integer, List<Integer>, List<Integer>>> set : monsterBookSets.entrySet()) {
                if (set.getValue().mid.contains(itemId)) {
                    ret.cardSet = set.getKey();
                    break;
                }
            }
        }

        final String scrollRq = sqlItemData.getString("scrollReqs");
        if (scrollRq.length() > 0) {
            ret.scrollReqs = new ArrayList<>();
            final String[] scroll = scrollRq.split(",");
            for (String s : scroll) {
                if (s.length() > 1) {
                    ret.scrollReqs.add(Integer.parseInt(s));
                }
            }
        }
        final String consumeItem = sqlItemData.getString("consumeItem");
        if (consumeItem.length() > 0) {
            ret.questItems = new ArrayList<>();
            final String[] scroll = scrollRq.split(",");
            for (String s : scroll) {
                if (s.length() > 1) {
                    ret.questItems.add(Integer.parseInt(s));
                }
            }
        }

        ret.totalprob = sqlItemData.getInt("totalprob");
        final String incRq = sqlItemData.getString("incSkill");
        if (incRq.length() > 0) {
            ret.incSkill = new ArrayList<>();
            final String[] scroll = incRq.split(",");
            for (String s : scroll) {
                if (s.length() > 1) {
                    ret.incSkill.add(Integer.parseInt(s));
                }
            }
        }
        farmDataCache.put(itemId, ret);
    }

    public static MapleInventoryType getInventoryType(final int itemId) {
        final byte type = (byte) (itemId / 1000000);
        if (type < 1 || type > 5) {
            return MapleInventoryType.UNDEFINED;
        }
        return MapleInventoryType.getByType(type);
    }

    public short getPetFlagInfo(int itemId) {
        if (this.petFlagInfo.containsKey(itemId)) {
            return (this.petFlagInfo.get(itemId));
        }
        short flag = 0;
        if (itemId / 10000 != 500) {
            return flag;
        }
        MapleData item = getItemData(itemId);
        if (item == null) {
            return flag;
        }
        if (MapleDataTool.getIntConvert("info/pickupItem", item, 0) > 0) {
            flag = (short) (flag | MaplePet.PetFlag.ITEM_PICKUP.getValue());
        }
        if (MapleDataTool.getIntConvert("info/longRange", item, 0) > 0) {
            flag = (short) (flag | MaplePet.PetFlag.EXPAND_PICKUP.getValue());
        }
        if (MapleDataTool.getIntConvert("info/pickupAll", item, 0) > 0) {
            flag = (short) (flag | MaplePet.PetFlag.AUTO_PICKUP.getValue());
        }
        if (MapleDataTool.getIntConvert("info/sweepForDrop", item, 0) > 0) {
            flag = (short) (flag | MaplePet.PetFlag.LEFTOVER_PICKUP.getValue());
        }
        if (MapleDataTool.getIntConvert("info/consumeHP", item, 0) > 0) {
            flag = (short) (flag | MaplePet.PetFlag.HP_CHARGE.getValue());
        }
        if (MapleDataTool.getIntConvert("info/consumeMP", item, 0) > 0) {
            flag = (short) (flag | MaplePet.PetFlag.MP_CHARGE.getValue());
        }
        if (MapleDataTool.getIntConvert("info/autoBuff", item, 0) > 0) {
            flag = (short) (flag | MaplePet.PetFlag.PET_DIALOGUE.getValue());
        }
        this.petFlagInfo.put(itemId, flag);
        return flag;
    }

    public int getItemIncMHPr(int itemId) {
        if ((getEquipStats(itemId) == null) || (!getEquipStats(itemId).containsKey("MHPr"))) {
            return 0;
        }
        return (getEquipStats(itemId).get("MHPr"));
    }

    public int getItemIncMMPr(int itemId) {
        if ((getEquipStats(itemId) == null) || (!getEquipStats(itemId).containsKey("MMPr"))) {
            return 0;
        }
        return (getEquipStats(itemId).get("MMPr"));
    }

    public boolean isSuperiorEquip(int itemId) {
        Map<String, Integer> equipStats = getEquipStats(itemId);
        if (equipStats == null) {
            return false;
        }
        return equipStats.containsKey("superiorEqp") && equipStats.get("superiorEqp") == 1;
    }

    public int[] getExpPotionLev(int itemId) {
        if (expPotionLev.containsKey(itemId)) {
            return expPotionLev.get(itemId);
        }
        MapleData data = getItemData(itemId);
        int[] lev = new int[]{MapleDataTool.getIntConvert("info/exp/minLev", data, 1),
            MapleDataTool.getIntConvert("info/exp/maxLev", data, 1)};
        expPotionLev.put(itemId, lev);
        return lev;
    }

    public List<Integer> getCanAccountSharable(int itemId) {
        if (canAccountSharable.containsKey(itemId)) {
            return canAccountSharable.get(itemId);
        }
        MapleData data = getItemData(itemId);
        List<Integer> jobs = new ArrayList<>();
        data = data.getChildByPath("info/canAccountSharable/job");
        if (data != null) {
            for (MapleData d : data.getChildren()) {
                jobs.add(Integer.parseInt(d.getName()));
            }
        }
        canAccountSharable.put(itemId, jobs);
        return canAccountSharable.get(itemId);
    }

    public List<Integer> getCantAccountSharable(int itemId) {
        if (cantAccountSharable.containsKey(itemId)) {
            return cantAccountSharable.get(itemId);
        }
        MapleData data = getItemData(itemId);
        List<Integer> jobs = new ArrayList<>();
        data = data.getChildByPath("info/cantAccountSharable/job");
        if (data != null) {
            for (MapleData d : data.getChildren()) {
                jobs.add(Integer.parseInt(d.getName()));
            }
        }
        cantAccountSharable.put(itemId, jobs);
        return cantAccountSharable.get(itemId);
    }

    public boolean isNoCursedScroll(int itemId) {
        if (noCursedScroll.containsKey(itemId)) {
            return noCursedScroll.get(itemId);
        }
        if (itemId / 10000 != 204) {
            return false;
        }
        boolean noCursed = MapleDataTool.getIntConvert("info/noCursed", getItemData(itemId), 0) > 0;
        noCursedScroll.put(itemId, noCursed);
        return noCursed;
    }

    public boolean isNegativeScroll(int itemId) {
        if (this.noNegativeScroll.containsKey(itemId)) {
            return (this.noNegativeScroll.get(itemId));
        }
        if (itemId / 10000 != 204) {
            return false;
        }
        boolean noNegative = MapleDataTool.getIntConvert("info/noNegative", getItemData(itemId), 0) > 0;
        this.noNegativeScroll.put(itemId, noNegative);
        return noNegative;
    }

    public int getForceUpgrade(int itemId) {
        if (this.forceUpgrade.containsKey(itemId)) {
            return (this.forceUpgrade.get(itemId));
        }
        int upgrade = 0;
        if (itemId / 100 != 20493) {
            return upgrade;
        }
        upgrade = MapleDataTool.getIntConvert("info/forceUpgrade", getItemData(itemId), 1);
        this.forceUpgrade.put(itemId, upgrade);
        return upgrade;
    }

    public Pair<Integer, Integer> getChairRecovery(int itemId) {
        if (itemId / 10000 != 301) {
            return null;
        }
        if (chairRecovery.containsKey(itemId)) {
            return chairRecovery.get(itemId);
        }
        int recoveryHP = MapleDataTool.getIntConvert("info/recoveryHP", getItemData(itemId), 0);
        int recoveryMP = MapleDataTool.getIntConvert("info/recoveryMP", getItemData(itemId), 0);
        Pair<Integer, Integer> ret = new Pair<>(recoveryHP, recoveryMP);
        chairRecovery.put(itemId, ret);
        return ret;
    }

    public int getLimitBreak(int itemId) {
        if ((getEquipStats(itemId) == null) || (!getEquipStats(itemId).containsKey("limitBreak"))) {
            return 999999;
        }
        return (getEquipStats(itemId).get("limitBreak"));
    }

    public int getLife(int itemId) {
        if (getEquipStats(itemId) == null || !getEquipStats(itemId).containsKey("life")) {
            return 90;
        }
        return getEquipStats(itemId).get("life");
    }

    public int getLimitedLife(int itemId) {
        if (getEquipStats(itemId) == null || !getEquipStats(itemId).containsKey("limitedLife")) {
            return 0;
        }
        return getEquipStats(itemId).get("limitedLife");
    }

    public Equip resetEquipStats(Equip oldEquip, boolean prefectReset) {
        Equip newEquip = (Equip) getEquipById(oldEquip.getItemId());
        oldEquip.reset(newEquip, prefectReset);
        return newEquip;
    }

    public final boolean isOnlyTradeBlock(final int itemId) {
        final MapleData data = getItemData(itemId);
        boolean tradeblock = false;
        if (MapleDataTool.getIntConvert("info/tradeBlock", data, 0) == 1) {
            tradeblock = true;
        }
        return tradeblock;
    }

    public final double getExpCardRate(final int itemId) {
        return MapleDataTool.getIntConvert("info/rate", getItemData(itemId), 100) / 100.0;
    }

    public final int getExpCardMaxLevel(final int itemId) {
        return MapleDataTool.getIntConvert("info/maxLevel", getItemData(itemId), 249);
    }

    public boolean isExpOrDropCardTime(int itemId) {
        Calendar cal = Calendar.getInstance();
        String day = MapleDayInt.getDayInt(cal.get(7));
        Map<String, String> times;
        if (expCardTimes.containsKey(itemId)) {
            times = expCardTimes.get(itemId);
        } else {
            List<MapleData> data = getItemData(itemId).getChildByPath("info").getChildByPath("time").getChildren();
            Map<String, String> hours = new HashMap<>();
            for (MapleData childdata : data) {
                String[] time = MapleDataTool.getString(childdata).split(":");
                hours.put(time[0], time[1]);
            }
            times = hours;
            expCardTimes.put(itemId, hours);
            cal.get(Calendar.DAY_OF_WEEK);
        }
        if (times.containsKey(day)) {
            String[] hourspan = times.get(day).split("-");
            int starthour = Integer.parseInt(hourspan[0]);
            int endhour = Integer.parseInt(hourspan[1]);

            if (cal.get(Calendar.HOUR_OF_DAY) >= starthour && cal.get(Calendar.HOUR_OF_DAY) <= endhour) {
                return true;
            }
        }
        return false;
    }

    public static class MapleDayInt {

        public static String getDayInt(int day) {
            if (day == 1) {
                return "SUN";
            }
            if (day == 2) {
                return "MON";
            }
            if (day == 3) {
                return "TUE";
            }
            if (day == 4) {
                return "WED";
            }
            if (day == 5) {
                return "THU";
            }
            if (day == 6) {
                return "FRI";
            }
            if (day == 7) {
                return "SAT";
            }
            return null;
        }
    }

    public Pair<Integer, Integer> getSocketReqLevel(int itemId) {
        int socketId = itemId % 1000 + 1;
        if (!socketReqLevel.containsKey(socketId)) {
            MapleData skillOptionData = itemData.getData("SkillOption.img");
            MapleData socketData = skillOptionData.getChildByPath("socket");
            int reqLevelMax = MapleDataTool.getIntConvert(socketId + "/reqLevelMax", socketData, 250);
            int reqLevelMin = MapleDataTool.getIntConvert(socketId + "/reqLevelMin", socketData, 70);
            socketReqLevel.put(socketId, new Pair<>(reqLevelMax, reqLevelMin));
        }
        return socketReqLevel.get(socketId);
    }

    public int getSoulSkill(int itemId) {
        int soulName = itemId % 1000 + 1;
        if (!soulSkill.containsKey(soulName)) {
            MapleData skillOptionData = itemData.getData("SkillOption.img");
            MapleData skillData = skillOptionData.getChildByPath("skill");
            int skillId = MapleDataTool.getIntConvert(soulName + "/skillId", skillData, 0);
            soulSkill.put(soulName, skillId);
        }
        return soulSkill.get(soulName);
    }

    public ArrayList<Integer> getTempOption(int itemId) {
        int soulName = itemId % 1000 + 1;
        if (!tempOption.containsKey(soulName)) {
            MapleData skillOptionData = itemData.getData("SkillOption.img");
            MapleData tempOptionData = skillOptionData.getChildByPath("skill/" + soulName + "/tempOption");
            ArrayList<Integer> pots = new ArrayList<>();
            for (MapleData pot : tempOptionData.getChildren()) {
                pots.add(MapleDataTool.getIntConvert("id", pot, 1));
            }
            tempOption.put(soulName, pots);
        }
        return tempOption.get(soulName);
    }

    public int getAndroidType(int itemId) {
        if (androidType.containsKey(itemId)) {
            return (androidType.get(itemId));
        }
        int type = 0;
        if (itemId / 10000 != 166) {
            return type;
        }
        type = MapleDataTool.getIntConvert("info/android", getItemData(itemId), 1);
        androidType.put(itemId, type);
        return type;
    }
}
