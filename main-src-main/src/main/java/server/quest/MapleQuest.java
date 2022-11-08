package server.quest;

import client.MapleCharacter;
import client.MapleJob;
import client.MapleQuestStatus;
import database.ManagerDatabasePool;
import scripting.NPCScriptManager;
import server.farm.MapleFarmQuestRequirement;
import tools.Pair;
import tools.StringUtil;
import tools.packet.CField.EffectPacket;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;

public class MapleQuest implements Serializable {

    private static final long serialVersionUID = 9179541993413738569L;
    private static final Map<Integer, MapleQuest> quests = new TreeMap<>(Comparator.naturalOrder());
    private static final Map<Integer, MapleQuest> farmQuests = new TreeMap<>(Comparator.naturalOrder());
    protected int id;
    protected final List<MapleQuestRequirement> startReqs = new LinkedList<>();
    protected final List<MapleQuestRequirement> completeReqs = new LinkedList<>();
    protected final List<MapleFarmQuestRequirement> farmStartReqs = new LinkedList<>();
    protected final List<MapleFarmQuestRequirement> farmCompleteReqs = new LinkedList<>();
    protected final List<MapleQuestAction> startActs = new LinkedList<>();
    protected final List<MapleQuestAction> completeActs = new LinkedList<>();
    protected final Map<String, List<Pair<String, Pair<String, Integer>>>> partyQuestInfo = new LinkedHashMap<>(); // [rank,
    // [more/less/equal,
    // [property,
    // value]]]
    protected final Map<Integer, Integer> relevantMobs = new LinkedHashMap<>();
    private boolean autoStart = false, autoPreComplete = false, repeatable = false, customend = false, blocked = false,
            autoAccept = false, autoComplete = false, scriptedStart = false;
    private int viewMedalItem = 0, selectedSkillID = 0;
    protected String name = "";

    protected MapleQuest(final int id) {
        this.id = id;
    }

    private static MapleQuest loadQuest(ResultSet rs, PreparedStatement psr, PreparedStatement psa,
            PreparedStatement pss, PreparedStatement psq, PreparedStatement psi, PreparedStatement psp)
            throws SQLException {
        final MapleQuest ret = new MapleQuest(rs.getInt("questid"));
        ret.name = rs.getString("name");
        ret.autoStart = rs.getInt("autoStart") > 0;
        ret.autoPreComplete = rs.getInt("autoPreComplete") > 0;
        ret.autoAccept = rs.getInt("autoAccept") > 0;
        ret.autoComplete = rs.getInt("autoComplete") > 0;
        ret.viewMedalItem = rs.getInt("viewMedalItem");
        ret.selectedSkillID = rs.getInt("selectedSkillID");
        ret.blocked = rs.getInt("blocked") > 0; // ult.explorer quests will dc as the item isn't there...

        psr.setInt(1, ret.id);
        ResultSet rse = psr.executeQuery();
        while (rse.next()) {
            final MapleQuestRequirementType type = MapleQuestRequirementType.getByWZName(rse.getString("name"));
            final MapleQuestRequirement req = new MapleQuestRequirement(ret, type, rse);
            switch (type) {
                case interval:
                    ret.repeatable = true;
                    break;
                case normalAutoStart:
                    ret.repeatable = true;
                    ret.autoStart = true;
                    break;
                case startscript:
                    ret.scriptedStart = true;
                    break;
                case endscript:
                    ret.customend = true;
                    break;
                case mob:
                    for (Pair<Integer, Integer> mob : req.getDataStore()) {
                        ret.relevantMobs.put(mob.left, mob.right);
                    }
                    break;
                default:
                    break;
            }
            if (rse.getInt("type") == 0) {
                ret.startReqs.add(req);
            } else {
                ret.completeReqs.add(req);
            }
        }
        rse.close();

        psa.setInt(1, ret.id);
        rse = psa.executeQuery();
        while (rse.next()) {
            final MapleQuestActionType ty = MapleQuestActionType.getByWZName(rse.getString("name"));
            if (rse.getInt("type") == 0) { // pass it over so it will set ID + type once done
                if (ty == MapleQuestActionType.item && ret.id == 7103) { // pap glitch
                    continue;
                }
                ret.startActs.add(new MapleQuestAction(ty, rse, ret, pss, psq, psi));
            } else {
                if (ty == MapleQuestActionType.item && ret.id == 7102) { // pap glitch
                    continue;
                }
                ret.completeActs.add(new MapleQuestAction(ty, rse, ret, pss, psq, psi));
            }
        }
        rse.close();

        psp.setInt(1, ret.id);
        rse = psp.executeQuery();
        while (rse.next()) {
            if (!ret.partyQuestInfo.containsKey(rse.getString("rank"))) {
                ret.partyQuestInfo.put(rse.getString("rank"), new ArrayList<Pair<String, Pair<String, Integer>>>());
            }
            ret.partyQuestInfo.get(rse.getString("rank"))
                    .add(new Pair<>(rse.getString("mode"), new Pair<>(rse.getString("property"), rse.getInt("value"))));
        }
        rse.close();
        return ret;
    }

    private static MapleQuest loadFarmQuest(ResultSet rs, PreparedStatement psr) throws SQLException {
        final MapleQuest ret = new MapleQuest(rs.getInt("questid"));
        ret.name = rs.getString("name");
        ret.repeatable = rs.getInt("repeatable") > 0;

        psr.setInt(1, ret.id);
        try (ResultSet rse = psr.executeQuery()) {
            while (rse.next()) {
                final MapleFarmQuestRequirement req = new MapleFarmQuestRequirement(ret, ret.repeatable, rse);
                if (rse.getInt("type") == 0) {
                    ret.farmStartReqs.add(req);
                } else {
                    ret.farmCompleteReqs.add(req);
                }
            }
        }
        return ret;
    }

    public List<Pair<String, Pair<String, Integer>>> getInfoByRank(final String rank) {
        return partyQuestInfo.get(rank);
    }

    public boolean isPartyQuest() {
        return partyQuestInfo.size() > 0;
    }

    public final int getSkillID() {
        return selectedSkillID;
    }

    public final String getName() {
        return name;
    }

    public final List<MapleQuestAction> getCompleteActs() {
        return completeActs;
    }

    public static void initQuests(boolean reload) {
        if (reload) {
            quests.clear();
            farmQuests.clear();
        }
        if (!quests.isEmpty() || !farmQuests.isEmpty()) {
            return;
        }
        try {
            Connection con = ManagerDatabasePool.getConnection();
            PreparedStatement psr;
            PreparedStatement psa;
            PreparedStatement pss;
            PreparedStatement psq;
            PreparedStatement psi;
            PreparedStatement psp;
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM wz_questdata")) {
                psr = con.prepareStatement("SELECT * FROM wz_questreqdata WHERE questid = ?");
                psa = con.prepareStatement("SELECT * FROM wz_questactdata WHERE questid = ?");
                pss = con.prepareStatement("SELECT * FROM wz_questactskilldata WHERE uniqueid = ?");
                psq = con.prepareStatement("SELECT * FROM wz_questactquestdata WHERE uniqueid = ?");
                psi = con.prepareStatement("SELECT * FROM wz_questactitemdata WHERE uniqueid = ?");
                psp = con.prepareStatement("SELECT * FROM wz_questpartydata WHERE questid = ?");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        quests.put(rs.getInt("questid"), loadQuest(rs, psr, psa, pss, psq, psi, psp));
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM wz_farmquestdata")) {
                psr = con.prepareStatement("SELECT * FROM wz_farmquestreqdata WHERE questid = ?");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        farmQuests.put(rs.getInt("questid"), loadFarmQuest(rs, psr));
                    }
                }
            }
            psr.close();
            psa.close();
            pss.close();
            psq.close();
            psi.close();
            psp.close();
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException e) {
        }
    }

    public static MapleQuest getInstance(int id) {
        MapleQuest ret = quests.get(id);
        if (ret == null) {
            ret = new MapleQuest(id);
            quests.put(id, ret); // by this time we have already initialized
        }
        return ret;
    }

    public static MapleQuest getFarmInstance(int id) {
        MapleQuest ret = farmQuests.get(id);
        if (ret == null) {
            ret = new MapleQuest(id);
            farmQuests.put(id, ret); // by this time we have already initialized
        }
        return ret;
    }

    public static Collection<MapleQuest> getAllInstances() {
        return quests.values();
    }

    public static Collection<MapleQuest> getAllFarmInstances() {
        return farmQuests.values();
    }

    public boolean canStart(MapleCharacter c, Integer npcid) {
        if (c.getQuest(this).getStatus() != 0 && !(c.getQuest(this).getStatus() == 2 && repeatable)) {
            return false;
        }
        if (blocked) {
            return false;
        }
        if (autoAccept) {
            return true; // need script
        }
        boolean jobs = true;
        for (MapleQuestRequirement r : startReqs) {
            if (r.getType() == MapleQuestRequirementType.dayByDay && npcid != null) { // everyday. we don't want ok
                forceComplete(c, npcid);
                return false;
            }
            if (!r.check(c, npcid)) {
                switch (r.getType()) {
                    case job:
                    case job_TW:
                        if (jobs) {
                            jobs = !jobs;
                            continue;
                        }
                        break;
                }
                return false;
            }
        }
        return true;
    }

    public boolean canComplete(MapleCharacter c, Integer npcid) {
        if (c.getQuest(this).getStatus() != 1) {
            return false;
        }
        if (blocked && id != 23205) { // 惡魔殺手劇情任務
            return false;
        }
        for (MapleQuestRequirement r : completeReqs) {
            if (!r.check(c, npcid)) {
                return false;
            }
        }
        return true;
    }

    public boolean canCompleteFarm(MapleCharacter c) {
        if (c.getQuest(this).getStatus() != 1) {
            return false;
        }
        for (MapleFarmQuestRequirement r : farmCompleteReqs) {
            if (!r.check(c, repeatable)) {
                return false;
            }
        }
        return true;
    }

    public final void RestoreLostItem(final MapleCharacter c, final int itemid) {
        /*
         * if (blocked && !c.isGM()) { return; }
         */
        for (final MapleQuestAction a : startActs) {
            if (a.RestoreLostItem(c, itemid)) {
                break;
            }
        }
    }

    public void start(MapleCharacter chr, int npc) {
        if (chr.isShowInfo()) {
            chr.dropMessage(6,
                    new StringBuilder().append("開始任務 npc: ").append(npc).append(" autoStart：").append(this.autoStart)
                            .append(" checkNPCOnMap: ").append(checkNPCOnMap(chr, npc)).append(" canStart: ")
                            .append(canStart(chr, npc)).toString());
        }
        if ((autoStart || checkNPCOnMap(chr, npc)) && canStart(chr, npc)) {
            for (MapleQuestAction a : startActs) {
                if (!a.checkEnd(chr, null)) { // just in case
                    return;
                }
            }
            for (MapleQuestAction a : startActs) {
                a.runStart(chr, null);
            }
            if (!customend) {
                forceStart(chr, npc, null);
            } else {
                NPCScriptManager.getInstance().startQuest(chr.getClient(), npc, getId());
            }
        }
    }

    public void complete(MapleCharacter c, int npc) {
        complete(c, npc, null);
    }

    public void complete(MapleCharacter c, int npc, Integer selection) {
        if (c.getMap() != null && (autoPreComplete || autoComplete || checkNPCOnMap(c, npc)) && canComplete(c, npc)) {
            for (MapleQuestAction a : completeActs) {
                if (!a.checkEnd(c, selection)) {
                    return;
                }
            }
            forceComplete(c, npc);
            for (MapleQuestAction a : completeActs) {
                a.runEnd(c, selection);
            }
            // we save forfeits only for logging purposes, they shouldn't matter anymore
            // completion time is set by the constructor

            c.getClient().getSession().writeAndFlush(EffectPacket.showQuetCompleteEffect());
            c.getMap().broadcastMessage(c, EffectPacket.showQuetCompleteEffect(c), false);
        }
    }

    public void forfeit(MapleCharacter c) {
        if (c.getQuest(this).getStatus() != (byte) 1) {
            return;
        }
        final MapleQuestStatus oldStatus = c.getQuest(this);
        final MapleQuestStatus newStatus = new MapleQuestStatus(this, (byte) 0);
        newStatus.setForfeited(oldStatus.getForfeited() + 1);
        newStatus.setCompletionTime(oldStatus.getCompletionTime());
        c.updateQuest(newStatus);
    }

    public void forceStart(MapleCharacter c, int npc, String customData) {
        final MapleQuestStatus newStatus = new MapleQuestStatus(this, (byte) 1, npc);
        newStatus.setForfeited(c.getQuest(this).getForfeited());
        newStatus.setCompletionTime(c.getQuest(this).getCompletionTime());
        newStatus.setCustomData(customData);
        c.updateQuest(newStatus);
    }

    public void forceStartHillaGang(List<MapleCharacter> party, int npc, String customData) {
        for (MapleCharacter chr : party) {
            final MapleQuestStatus newStatus = new MapleQuestStatus(this, (byte) 1, npc);
            newStatus.setForfeited(chr.getQuest(this).getForfeited());
            newStatus.setCompletionTime(chr.getQuest(this).getCompletionTime());
            newStatus.setCustomData(customData);
            chr.updateQuest(newStatus);
        }
    }

    public void forceComplete(MapleCharacter c, int npc) {
        final MapleQuestStatus newStatus = new MapleQuestStatus(this, (byte) 2, npc);
        newStatus.setForfeited(c.getQuest(this).getForfeited());
        c.updateQuest(newStatus);
        c.getClient().getSession().writeAndFlush(EffectPacket.showQuetCompleteEffect());
        c.getMap().broadcastMessage(c, EffectPacket.showQuetCompleteEffect(c), false);
    }

    public int getId() {
        return id;
    }

    public Map<Integer, Integer> getRelevantMobs() {
        return relevantMobs;
    }

    private boolean checkNPCOnMap(MapleCharacter player, int npcid) {
        // mir = 1013000
        return npcid == 0 || (MapleJob.is龍魔導士(player.getJob()) && npcid == 1013000)
                || (MapleJob.is天使破壞者(player.getJob()) && npcid == 3000018) || npcid == 9000040 || npcid == 9000066
                || (player.getMap() != null && player.getMap().containsNPC(npcid));
    }

    public int getMedalItem() {
        return viewMedalItem;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public static enum MedalQuest {

        新手探險家(29005, 29015, 15, new int[]{100000000, 100020400, 100040000, 101000000, 101020300, 101040300, 102000000,
            102020500, 102030400, 102040200, 103000000, 103020200, 103030400, 103040000, 104000000, 104020000,
            106020100, 120000000, 120020400, 120030000}), 冰原雪域山脈探險家(
                29006, 29012, 50,
                new int[]{200000000, 200010100, 200010300, 200080000, 200080100, 211000000, 211030000,
                    211040300, 211041200, 211041800}), 路德斯湖探險家(
                29007, 29012, 40,
                new int[]{222000000, 222010400, 222020000, 220000000, 220020300, 220040200,
                    221020701, 221000000, 221030600, 221040400}), 海底探險家(
                29008, 29012, 40,
                new int[]{230000000, 230010400, 230010200, 230010201,
                    230020000, 230020201, 230030100, 230040000, 230040200,
                    230040400}), 武陵桃園探險家(
                29009, 29012, 50,
                new int[]{251000000, 251010200, 251010402,
                    251010500, 250010500, 250010504,
                    250000000, 250010300, 250010304,
                    250020300}), 納希沙漠探險家(
                29010, 29012, 70,
                new int[]{261030000,
                    261020401, 261020000,
                    261010100, 261000000,
                    260020700, 260020300,
                    260000000, 260010600,
                    260010300}), 米納爾森林探險家(
                29011, 29012,
                70,
                new int[]{
                    240000000,
                    240010200,
                    240010800,
                    240020401,
                    240020101,
                    240030000,
                    240040400,
                    240040511,
                    240040521,
                    240050000}), 奇幻村探險家(
                29014,
                29015,
                50,
                new int[]{
                    105000000,
                    105000000,
                    105010100,
                    105020100,
                    105020300,
                    105030000,
                    105030100,
                    105030300,
                    105030500,
                    105030500}); // repeated
        // map
        public int questid, level, lquestid;
        public int[] maps;

        private MedalQuest(int questid, int lquestid, int level, int[] maps) {
            this.questid = questid; // infoquest = questid -2005, customdata = questid -1995
            this.level = level;
            this.lquestid = lquestid;
            this.maps = maps; // note # of maps
        }
    }

    public boolean hasStartScript() {
        return scriptedStart;
    }

    public boolean hasEndScript() {
        return customend;
    }

    public static String formatInfoString(Map<String, Object> args) {
        String result = "";
        int i = 0;
        for (Entry<String, Object> arg : args.entrySet()) {
            result += arg.getKey();
            result += '=';
            result += String.valueOf(arg.getValue());
            if (args.size() - 1 > i) {
                result += ";";
            }
            i++;
        }
        return result;
    }

    public static Map<String, Object> decodeInfoString(String args) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String arg : args.split(";")) {
            String[] entry = arg.split("=");
            String key = entry[0];
            String value = entry[1];
            if (StringUtil.isNumber(entry[1])) {
                try {
                    result.put(key, Integer.parseInt(value));
                    continue;
                } catch (Exception ex) {
                }
            }
            result.put(key, value);
        }
        return result;
    }

    @Override
    public String toString() {
        return getName() + "(" + getId() + ")";
    }
}
